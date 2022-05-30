(ns space.matterandvoid.subscriptions.impl.fulcro-subscriptions
  "Automatically register subscriptions to fulfill EQL queries for fulcro components."
  (:require
    [com.fulcrologic.fulcro.raw.components :as rc]
    [com.fulcrologic.fulcro.application :as fulcro.app]
    [space.matterandvoid.subscriptions.fulcro :as subs :refer [reg-sub reg-sub-raw subscribe <sub]]
    [space.matterandvoid.subscriptions.impl.reagent-ratom :as r :refer [make-reaction]]
    [sc.api]
    [edn-query-language.core :as eql]))

(defn nc
  "Wrap fulcro.raw.components/nc to be more uniform and require explicit options instead of implicit for normalizing
  components."
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

(defn app->db [fulcro-app] (fulcro.app/current-state fulcro-app))

(defn group-by-flat [f coll] (persistent! (reduce #(assoc! %1 (f %2) %2) (transient {}) coll)))

(def missing-val ::missing)

(defn eql-by-key [query] (group-by-flat :dispatch-key (:children (eql/query->ast query))))
(defn ast-by-key->query [k->ast] (vec (mapcat eql/ast->query (vals k->ast))))

(defn recur? [q] (or (= '... q) (= 0 q) (pos-int? q)))
(defn error [& args] #?(:clj (Exception. ^String (apply str args)) :cljs (js/Error. (apply str args))))

(defn union-key->entity-sub [union-ast]
  (reduce (fn [acc {:keys [union-key component]}]
            (let [reg-key (rc/class->registry-key component)]
              (when-not reg-key (throw (error "missing union component name for key: " union-key)))
              (assoc acc union-key reg-key)))
    {}
    (:children union-ast)))

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
        union-joins       (set (map (fn [{:keys [dispatch-key children] :as ast}]
                                      (let [union-key->entity    (union-key->entity-sub (first children))
                                            _                    (println "union-key->entity " union-key->entity)
                                            union-key->component [dispatch-key (fn [kw]
                                                                                 (println "union-key to comp args: " kw)
                                                                                 (assert (keyword? kw))
                                                                                 (kw union-key->entity))]]
                                        (println "union-key->component " union-key->component)
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
  [id-attr prop]
  (reg-sub prop
    (fn [db args]
      (println "in plain prop: " prop)
      (if-let [entity-id (get args id-attr)]
        (let [entity (get-in db [id-attr entity-id])]
          (get entity prop))
        (throw (error "Missing id attr: " id-attr " in args map passed to subscription: " prop "\nArgs: " args))))))

(defn reg-sub-entity
  "Registers a subscription that returns a domain entity as a hashmap.
  id-attr for the entity, the entity subscription name (fq kw) a seq of dependent subscription props"
  [id-kw entity-kw props]
  (reg-sub-raw entity-kw
    (fn [app args]
      (println "REG sub entityt: " args)
      (if (::subs/query args)
        (let [props->ast (eql-by-key (::subs/query args))
              props'     (keys props->ast)]
          (make-reaction
            (fn []
              (let [output
                    (reduce (fn [acc prop]
                              (let [output
                                    (do
                                      (println "entity sub, sub-query for: " prop)
                                      (<sub app [prop (assoc args
                                                        ;; to implement recursive queries
                                                        ::subs/parent-query (::subs/query args)
                                                        ::subs/query (:query (props->ast prop)))]))]
                                (cond-> acc
                                  (not= missing-val output)
                                  (assoc prop output))))
                      {} props')]
                (println "output: " output)
                output))))
        (make-reaction
          (fn [] (reduce (fn [acc prop] (assoc acc prop (<sub app [prop args]))) {} props)))
        ))))

;; todo need to test to-one and to-many joins
;; todo to-one recur and to-many
;; todo need to test to-one and to-many recur joins

(defn reg-sub-plain-join
  "Takes two keywords: id attribute and property attribute, registers a layer 2 subscription using the id to lookup the
  entity and extract the property."
  [id-attr join-prop join-component-sub]
  (reg-sub-raw join-prop
    (fn [app args]
      (make-reaction
        (fn []
          (println " in plain join prop: " join-prop)
          (println " in plain join join-comp sub: " join-component-sub)
          (println " in plain join args: " args)
          (if-let [entity-id (get args id-attr)]
            (let [entity    (get-in (app->db app) [id-attr entity-id])
                  rels      (not-empty (get entity join-prop))
                  query     (::subs/query args)
                  query-ast (eql/query->ast query)]
              (def query' query-ast)
              (comment (sc.api/defsc 1))
              (sc.api/spy
                (cond
                  (eql/ident? rels)
                  (do
                    (println "HAVE single ident: " rels)
                    (println "sub: " [join-component-sub (apply assoc args rels)])
                    (<sub app [join-component-sub (apply assoc args rels)]))
                  rels
                  (do
                    (println "HAVE many idents: " rels)
                    (if (::subs/query args)
                      (mapv (fn [[id v]]
                              ;; handle union
                              (<sub app [join-component-sub (assoc args id v)])) rels)
                      rels))

                  :else (throw (error "Plain join Invalid join: for join prop " join-prop, " value: " rels)))))
            (throw (error "Missing id attr: " id-attr " in args map passed to subscription: " join-prop))))))))

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
  [id-attr join-prop join-component-sub]
  (reg-sub-raw join-prop
    (fn [app args]
      (make-reaction
        (fn []
          (println " \n----in union join prop: " join-prop, "id attr: " id-attr)
          (println " in union join args: " args)
          (if-let [entity-id (get args id-attr)]
            (do
              (println "UNION have entity id: " entity-id)
              (let [entity           (get-in (app->db app) [id-attr entity-id])
                    rels             (not-empty (get entity join-prop))
                    query            (::subs/query args)
                    query-ast        (eql/query->ast query)
                    union-branch-map (union-query->branch-map join-prop (::subs/parent-query args))]
                (def args' args)
                (comment (union-query->branch-map join-prop (::subs/parent-query args')))
                (def b' union-branch-map)
                (def query-ast' query-ast)
                (def query' query)
                (cond
                  (eql/ident? rels)
                  (do
                    (println "union HAVE single ident: " rels)
                    (println "union sub: " [(join-component-sub (first rels)) (apply assoc args rels)])
                    (let [[kw id] rels]
                      (<sub app [(join-component-sub kw) (assoc args kw id ::subs/query (union-branch-map kw))])))

                  rels
                  (do
                    (println "HAVE many idents: " rels)
                    (if (::subs/query args)
                      (mapv (fn [[id v]]
                              (let [args' (assoc args id v ::subs/query (union-branch-map id))]
                                (<sub app [(join-component-sub id) args']))
                              ) rels)
                      rels))

                  :else (throw (error "Union Invalid join: for join prop " join-prop, " value: " rels)))))
            (throw (error "Missing id attr: " id-attr " in args map passed to subscription: " join-prop))))))))

(defn reg-sub-recur-join
  [id-attr recur-prop entity-sub]
  (reg-sub-raw recur-prop
    (fn [app {::subs/keys [parent-query] :as args}]
      (make-reaction
        (fn []
          (println "sub recur-join children: " recur-prop ", " args)
          (if-let [entity-id (get args id-attr)]
            (let [refs        (seq (filter some? (get-in (app->db app) [id-attr entity-id recur-prop])))
                  sub-q       (::subs/query args)
                  recur-query (when (and sub-q parent-query)
                                (println "IN both")
                                (let [by-key      (eql-by-key parent-query)
                                      ;; this is either an int or '...
                                      recur-value (:query (get by-key recur-prop))]
                                  (cond
                                    (= recur-value '...) parent-query
                                    (and (pos-int? recur-value) (> recur-value 0))
                                    (ast-by-key->query (update-in by-key [recur-prop :query] dec)))))]
              (println "---------------Refs: " refs)
              (println "---------------recur query: " recur-query)
              (let [r (cond
                        (and recur-query (not refs)) missing-val
                        (and recur-query refs)
                        ;(and refs max-recur-depth recur-depth (> max-recur-depth recur-depth))
                        (do
                          (println "RECUR")
                          (println " args" args)
                          (mapv (fn [[_ id]] (<sub app [entity-sub (assoc args
                                                                     ;:recur/depth (inc recur-depth) :recur/max-depth max-recur-depth
                                                                     ::subs/query recur-query
                                                                     id-attr id)]))
                                refs))
                        ;; do not recur
                        refs (do (println "NOT RECUR") (vec refs))
                        :else missing-val)]
                r))
            (throw (error "Missing id attr: " id-attr " in args map passed to subscription: " recur-prop))))))))

(defn component-id-prop [c] (first (rc/get-ident c {})))

(defn component-reg-subs!
  "Registers subscriptions that will fulfill the given fulcro component's query."
  [c]
  (when-not (rc/class->registry-key c)
    (throw (error "Component name missing on component: " c)))
  (let [query      (rc/get-query c)
        entity-sub (rc/class->registry-key c)
        id-attr    (component-id-prop c)
        {:keys [props plain-joins union-joins recur-joins all-children]} (eql-query-keys-by-type query)]
    (when-not id-attr (throw (error "Component missing ident: " c)))
    (run! (fn [p] (reg-sub-prop id-attr p)) props)
    (run! (fn [[p component-sub]] (reg-sub-plain-join id-attr p component-sub)) plain-joins)
    (run! (fn [[p component-sub]] (reg-sub-union-join id-attr p component-sub)) union-joins)
    (run! (fn [[p]] (reg-sub-recur-join id-attr p entity-sub)) recur-joins)
    (reg-sub-entity id-attr entity-sub all-children)
    nil))
