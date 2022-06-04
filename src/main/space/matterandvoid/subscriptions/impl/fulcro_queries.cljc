(ns space.matterandvoid.subscriptions.impl.fulcro-queries
  "Automatically register subscriptions to fulfill EQL queries for fulcro components."
  (:require
    [com.fulcrologic.fulcro.raw.components :as rc]
    [com.fulcrologic.fulcro.application :as fulcro.app]
    [space.matterandvoid.subscriptions.fulcro :as subs :refer [reg-sub reg-sub-raw <sub]]
    [space.matterandvoid.subscriptions.impl.reagent-ratom :refer [make-reaction]]
    [edn-query-language.core :as eql]
    [taoensso.timbre :as log]))

(defn nc
  "Wraps fulcro.raw.components/nc to take one hashmap of fulcro component options, supports :ident being a keyword.
  Args:
  :query - fulcro eql query
  :ident - kw or function
  :name -> same as :componentName
  Returns a fulcro component created by fulcro.raw.components/nc"
  [args]
  (assert (and (:name args) (keyword? (:name args))))
  (assert (and (:query args) (or (vector? (:query args)) (map? (:query args)))))
  (let [vec-query? (vector? (:query args))
        ident      (:ident args)]
    (when vec-query? (assert (and (:ident args) (or (keyword? (:ident args)) (fn? (:ident args))))))
    (rc/nc (:query args)
      (-> args
        (cond-> ident (assoc :ident (if (keyword? ident) (fn [_ props] [ident (ident props)]) ident)))
        (assoc :componentName (:name args))
        (dissoc :query :name)))))

(defn ->db [fulcro-app-or-db]
  (cond-> fulcro-app-or-db
    (fulcro.app/fulcro-app? fulcro-app-or-db)
    (fulcro.app/current-state)))

(defn group-by-flat [f coll] (persistent! (reduce #(assoc! %1 (f %2) %2) (transient {}) coll)))

(def cycle-marker ::subs/cycle)
(def missing-val ::subs/missing)
(def query-key ::subs/query)

(defn eql-by-key [query] (group-by-flat :dispatch-key (:children (eql/query->ast query))))
(defn eql-by-key-&-keys [query] (let [out (group-by-flat :dispatch-key (:children (eql/query->ast query)))]
                                  [out (keys out)]))

(defn ast-by-key->query [k->ast] (vec (mapcat eql/ast->query (vals k->ast))))

;; abstract over storage
(defprotocol IDataSource
  (-entity-id [this db id-attr args-query-map])
  (-entity [this db id-attr args-query-map])
  (-attr [this db id-attr attr args-query-map]))

(defn recur? [q] (or (= '... q) (= 0 q) (pos-int? q)))
(defn error [& args] #?(:clj (Exception. ^String (apply str args)) :cljs (js/Error. (apply str args))))

(defn union-key->entity-sub [union-ast]
  (reduce (fn [acc {:keys [union-key component]}]
            (let [reg-key (rc/class->registry-key component)]
              (when-not reg-key (throw (error "missing union component name for key: " union-key)))
              (assoc acc union-key reg-key)))
    {}
    (:children union-ast)))

;; todo could print args map to aid debugging, but want to dissoc internal keys first
(defn missing-id-check! [id-attr sub-kw args]
  (when-not (get args id-attr) (throw (error "Missing id attr: " id-attr " in args map passed to subscription: " sub-kw))))

(defn eql-query-keys-by-type
  [query]
  (let [set-keys          #(->> % (map :dispatch-key) set)
        query-nodes       (-> query (eql/query->ast) :children)
        {props :prop joins :join} (group-by :type query-nodes)
        unions            (filter eql/union-children? joins)
        union-keys        (set-keys unions)
        [recur-joins plain-joins] (split-with (comp recur? :query) joins)
        plain-joins       (remove #(contains? union-keys (:dispatch-key %)) plain-joins)
        ;;                                           todo \/ here you want to make this a fn that dynamically
        ;; maps for unions fn of argsmap -> entity sub {:comment/id ::comment :todo/id ::todo}
        plain-joins       (set (map (juxt :dispatch-key (comp rc/class->registry-key :component)) plain-joins))
        union-joins       (set (map (fn [{:keys [dispatch-key children]}]
                                      (let [union-key->entity    (union-key->entity-sub (first children))
                                            union-key->component [dispatch-key (fn [kw]
                                                                                 (assert (keyword? kw))
                                                                                 (kw union-key->entity))]]
                                        union-key->component))
                                 unions))
        missing-join-keys (filter (comp nil? second) plain-joins)]
    (when (seq missing-join-keys)
      (throw (error "All join properties must have a component name. Props missing names: " (mapv first missing-join-keys))))
    {:all-children      (reduce into [] [(set-keys joins) (set-keys props)])
     :unions            union-keys
     :joins             (set-keys joins)
     :props             (set-keys props)
     :recur-joins       (set (map (juxt :dispatch-key :query) recur-joins))
     :missing-join-keys missing-join-keys
     :union-joins       union-joins
     :plain-joins       plain-joins}))

(defn reg-sub-prop
  "Takes two keywords: id attribute and property attribute, registers a layer 2 subscription using the id to lookup the
  entity and extract the property."
  [datasource id-attr prop]
  (reg-sub prop (fn [db args]
                  (missing-id-check! id-attr prop args)
                  (-attr datasource db id-attr prop args))))

(defn get-all-props-shallow
  "Return hashmap of data attribute keywords -> subscription output implementation for '* queries"
  [datasource app id-attr props args]
  (let [entity (-entity datasource app id-attr args)]
    (reduce (fn [acc prop] (assoc acc prop (get entity prop missing-val))) {} props)))

(defn reg-sub-entity
  "Registers a subscription that returns a domain entity as a hashmap.
  id-attr for the entity, the entity subscription name (fq kw) a seq of dependent subscription props"
  [datasource id-attr entity-kw props]
  (reg-sub-raw entity-kw
    (fn [app args]
      (if (contains? args query-key)
        (let [props->ast  (eql-by-key (get args query-key args))
              props'      (keys (dissoc props->ast '*))
              query       (get args query-key)
              star-query  (get props->ast '*)
              star-query? (some? star-query)]
          (make-reaction
            (fn []
              (let [all-props (if star-query? (get-all-props-shallow datasource app id-attr props args) nil)
                    output
                              (if (or (nil? query) (= query '[*]))
                                (do
                                  ;(println " query: " query)
                                  (-entity datasource app id-attr args))
                                (reduce (fn [acc prop]
                                          (let [output
                                                (do
                                                  ;(println "entity sub, sub-query for: " prop)
                                                    (<sub app [prop (assoc args
                                                                      ;; to implement recursive queries
                                                                      ::parent-query query
                                                                      query-key (:query (props->ast prop)))]))]
                                            ;(println "sub result : " prop " -> " output)
                                            (cond-> acc
                                              (not= missing-val output)
                                              (assoc prop output))))
                                  {} props'))
                    output    (merge all-props output)]
                ;(println "reg-sub-entity output: " output)
                output))))
        (make-reaction
          (fn [] (reduce (fn [acc prop] (assoc acc prop (<sub app [prop args]))) {} props)))))))

(defn reg-sub-plain-join
  "Takes two keywords: id attribute and property attribute, registers a layer 2 subscription using the id to lookup the
  entity and extract the property."
  [datasource id-attr join-prop join-component-sub]
  (reg-sub-raw join-prop
    (fn [app args]
      (make-reaction
        (fn []
          (missing-id-check! id-attr join-prop args)
          (let [refs  (not-empty
                        (-attr datasource app id-attr join-prop args)
                        ;(args->entity-prop id-attr join-prop app args)
                        )
                query (get args query-key)]
            (cond
              (eql/ident? refs)
              (<sub app [join-component-sub (apply assoc args refs)])

              refs
              (cond->> refs query (mapv (fn [[id v]] (<sub app [join-component-sub (assoc args id v)]))))

              :else missing-val)))))))

(defn union-query->branch-map
  "Takes a union join query and returns a map of keyword of the branches of the join to the query for that branch."
  [join-prop union-join-q]
  (let [ast          (:children (eql/query->ast union-join-q))
        union-parent (first (filter (fn [{:keys [dispatch-key]}] (= dispatch-key join-prop)) ast))
        union-nodes  (-> union-parent :children first :children)]
    (reduce (fn [acc {:keys [union-key query]}] (assoc acc union-key query)) {} union-nodes)))

(defn reg-sub-union-join
  "Takes two keywords: id attribute and property attribute, registers a layer 2 subscription using the id to lookup the
  entity and extract the property."
  [datasource id-attr join-prop join-component-sub]
  (reg-sub-raw join-prop
    (fn [app args]
      (make-reaction
        (fn []
          (missing-id-check! id-attr join-prop args)

          (let [refs                 (-attr datasource app id-attr join-prop args)
                query                (get args query-key)
                union-branch-map     (union-query->branch-map join-prop (::parent-query args))
                branch-keys-in-query (set (keys union-branch-map))]
            (cond

              ;; to-one
              (eql/ident? refs)
              (let [[kw id] refs]
                (<sub app [(join-component-sub kw) (assoc args kw id, query-key (union-branch-map kw))]))

              ;; to-many
              refs
              (if query
                (->> refs
                  (filter (fn [[id]] (contains? branch-keys-in-query id)))
                  (mapv (fn [[id v]]
                          (let [args' (assoc args id v query-key (union-branch-map id))]
                            (<sub app [(join-component-sub id) args'])))))
                refs)
              :else (throw (error "Union Invalid join: for join prop " join-prop, " value: " refs)))))))))

(defn reg-sub-recur-join
  [datasource id-attr recur-prop entity-sub]
  (reg-sub-raw recur-prop
    (fn [app {::keys [entity-history parent-query] :as args}]
      (make-reaction
        (fn []
          (missing-id-check! id-attr recur-prop args)
          (let [entity-id           (get args id-attr)
                recur-idents        (-attr datasource app id-attr recur-prop args)
                ident?              (eql/ident? recur-idents)
                refs                (or (and ident? recur-idents) (seq (filter some? recur-idents)))
                sub-query           (get args query-key)
                seen-entity-id?     (contains? entity-history entity-id)
                [recur-query recur-value] (when (and sub-query parent-query)
                                            (let [by-key      (eql-by-key parent-query)
                                                  recur-value (:query (get by-key recur-prop))]
                                              [(cond
                                                 (= recur-value '...) (if seen-entity-id?
                                                                        (vec (mapcat eql/ast->query (vals (dissoc by-key recur-prop))))
                                                                        parent-query)
                                                 (and (pos-int? recur-value) (> recur-value 0))
                                                 (ast-by-key->query (update-in by-key [recur-prop :query] dec))

                                                 (symbol? recur-value)
                                                 (if-let [f (get args recur-value)]
                                                   (do
                                                     (when-not (ifn? f) (throw (error "Recursion callback is not a function for attr" id-attr
                                                                                 " recur prop: " recur-prop
                                                                                 " received: " f)))
                                                     (f (-> args
                                                          (assoc ::subs/current-id-attr id-attr
                                                                 ::subs/current-entity-sub entity-sub))))
                                                   (throw (error "Missing function implementation for symbol " recur-value
                                                            " in recursive position for id attribute: " id-attr " recursion attribute: " recur-prop))))
                                               recur-value]))
                self-join?          (and ident? (= refs [id-attr entity-id]))
                infinite-self-join? (and recur-query self-join? (= recur-value '...))]

            (cond
              ;; pointer was nil
              (and recur-query (not refs)) missing-val

              ;; self cycle
              infinite-self-join? cycle-marker

              ;; to-one join
              (and recur-query ident?)
              (if seen-entity-id?
                ;; cycle
                (-> (<sub app [entity-sub
                               (-> args
                                 (update ::depth (fnil inc 0))
                                 (update ::entity-history (fnil conj #{}) entity-id)
                                 (assoc query-key recur-query, id-attr (second refs)))])
                  (assoc recur-prop cycle-marker))

                (<sub app [entity-sub
                           (-> args
                             (update ::depth (fnil inc 0))
                             (update ::entity-history (fnil conj #{}) entity-id)
                             (assoc query-key recur-query, id-attr (second refs)))]))

              ;; to-many join
              (and recur-query refs)
              (if seen-entity-id?
                cycle-marker
                (mapv (fn [[_ id]] (<sub app [entity-sub
                                              (-> args
                                                (update ::entity-history (fnil conj #{}) entity-id)
                                                (assoc query-key recur-query id-attr id))]))
                      refs))

              ;; do not recur
              refs (vec refs)
              :else missing-val)))))))

(defn component-id-prop [c] (first (rc/get-ident c {})))

(defn reg-component-subs!
  "Registers subscriptions that will fulfill the given fulcro component's query.
  The component must have a name and so must any components in its query."
  [datasource c]
  (when-not (rc/class->registry-key c) (throw (error "Component name missing on component: " c)))
  (let [query      (rc/get-query c)
        entity-sub (rc/class->registry-key c)
        id-attr    (component-id-prop c)
        {:keys [props plain-joins union-joins recur-joins all-children]} (eql-query-keys-by-type query)]
    (when-not id-attr (throw (error "Component missing ident: " c)))
    (run! (fn [p] (reg-sub-prop datasource id-attr p)) props)
    (run! (fn [[p component-sub]] (reg-sub-plain-join datasource id-attr p component-sub)) plain-joins)
    (run! (fn [[p component-sub]] (reg-sub-union-join datasource id-attr p component-sub)) union-joins)
    (run! (fn [[p]] (reg-sub-recur-join datasource id-attr p entity-sub)) recur-joins)
    (reg-sub-entity datasource id-attr entity-sub all-children)
    nil))
