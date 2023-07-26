(ns space.matterandvoid.subscriptions.impl.eql-queries
  "Automatically register subscriptions to fulfill EQL queries for fulcro components."
  (:require
    [borkdude.dynaload :refer [dynaload]]
    [edn-query-language.core :as eql]
    [space.matterandvoid.subscriptions.impl.eql-protocols :as proto]
    [space.matterandvoid.subscriptions.impl.reagent-ratom :refer [make-reaction]]
    ;[taoensso.timbre :as log]
    [clojure.set :as set]))

(def query-key ::query)

;; in the future you can make this an option to output a keyword instead of using nil
;(def missing-val ::missing)

(def missing-val ::missing #_nil)
(def walk-fn-key ::walk-fn)
(def xform-fn-key ::xform-fn)

(defn default-class->registry-key [c] (:componentName c))
(defn default-get-ident [c props] ((:ident c) c props))
(defn default-get-query [c] (with-meta (:query c) {:component c}))
(defn default-nc [args]
  (assert (map? args))
  (assert (and (:name args) (keyword? (:name args))))
  (assert (and (:query args) (or (vector? (:query args)) (map? (:query args)))))
  (let [vec-query? (vector? (:query args))
        ident      (:ident args)]
    (when vec-query? (assert (and (:ident args) (or (keyword? (:ident args)) (fn? (:ident args))))))
    {:query         (:query args)
     :ident         (if (keyword? ident) (fn [_ props] [ident (ident props)]) ident)
     :componentName (:name args)}))

(def class->registry* (dynaload 'com.fulcrologic.fulcro.raw.components/class->registry-key {:default default-class->registry-key}))
(def get-ident* (dynaload 'com.fulcrologic.fulcro.raw.components/get-ident {:default default-get-ident}))
(def get-query* (dynaload 'com.fulcrologic.fulcro.raw.components/get-query {:default default-get-query}))
(def fulcro-nc (dynaload 'com.fulcrologic.fulcro.raw.components/nc {:default default-nc}))
(def fulcro-loaded? (dynaload 'com.fulcrologic.fulcro.raw.components/nc {:default false}))

;; Fulcro API used
(defn class->registry-key [component] (class->registry* component))
(defn get-ident [component props] (get-ident* component props))
(defn get-query [component] (get-query* component))

(defn nc-wrapper
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
    (fulcro-nc (:query args)
      (-> args
        (cond-> ident (assoc :ident (if (keyword? ident) (fn [_ props] [ident (ident props)]) ident)))
        (assoc :componentName (:name args))
        (dissoc :query :name)))))

(defn nc
  "Wraps fulcro.raw.components/nc to take one hashmap of fulcro component options, supports :ident being a keyword.
  Args:
  :query - fulcro eql query
  :ident - kw or function
  :name -> same as :componentName
  Returns a fulcro component created by fulcro.raw.components/nc"
  [args]
  (if @fulcro-loaded? (nc-wrapper args) (default-nc args)))

(defn group-by-flat [f coll] (persistent! (reduce #(assoc! %1 (f %2) %2) (transient {}) coll)))

(defn eql-by-key* [query] (group-by-flat :dispatch-key (:children (eql/query->ast query))))
(def eql-by-key (memoize eql-by-key*))

(defn ast-by-key->query [k->ast] (vec (mapcat eql/ast->query (vals k->ast))))

(defn recur? [q] (or (= '... q) (nat-int? q)))
(defn error [& args] #?(:clj (Exception. ^String (apply str args)) :cljs (js/Error. (apply str args))))

(defn union-key->entity-sub [union-ast]
  (reduce (fn [acc {:keys [union-key component]}]
            (let [reg-key (class->registry-key component)]
              (when-not reg-key (throw (error "missing union component name for key: " union-key)))
              (assoc acc union-key reg-key)))
    {}
    (:children union-ast)))

;; todo could print args map to aid debugging, but want to dissoc internal keys first
(defn missing-id-check! [id-attr sub-kw args]
  (when-not (get args id-attr) (throw (error "Missing id attr: " id-attr " in args map passed to subscription: " sub-kw))))

(defn component-id-prop [c] (first (get-ident c {})))
(defn -ast-node->id-prop [node] (-> node :component component-id-prop))

(defn eql-query-keys-by-type
  "Takes an EQL query parses it and returns a map of the members of the query for easier downstream consumption."
  [query join-ast->subscription join-ast->union-sub]
  (let [set-keys          #(->> % (map :dispatch-key) set)
        query-nodes       (-> query (eql/query->ast) :children)
        {props :prop joins :join} (group-by :type query-nodes)
        unions            (filter eql/union-children? joins)
        union-keys        (set-keys unions)
        [recur-joins plain-joins] (split-with (comp recur? :query) joins)
        plain-joins       (->> plain-joins
                            (remove #(contains? union-keys (:dispatch-key %)))
                            (map (juxt :dispatch-key join-ast->subscription -ast-node->id-prop))
                            (set))
        union-joins       (set (map (fn [{:keys [dispatch-key children] :as join-ast}]
                                      (let [union-key->entity    (union-key->entity-sub (first children))
                                            union-id-keys        (set (keys union-key->entity))
                                            union-key->component [dispatch-key (fn [entity]
                                                                                 (assert (map? entity))
                                                                                 (let [id-attr   (first (filter union-id-keys (keys entity)))
                                                                                       union-sub (join-ast->union-sub join-ast id-attr)]
                                                                                   [id-attr (get entity id-attr) union-sub]))]]
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

(defn get-all-props-shallow
  "Return hashmap of data attribute keywords -> subscription output implementation for '* queries"
  [datasource app id-attr props prop->ast args]
  (let [entity (proto/-entity datasource app id-attr args)]
    (reduce (fn [acc k]
              (let [xform-fn-sym (get-in (prop->ast k) [:params xform-fn-key])
                    xform-fn     (when xform-fn-sym (get args xform-fn-sym))]
                (cond-> acc
                  (contains? entity k)
                  (assoc! k (cond-> (get entity k) xform-fn xform-fn)))))
      (transient {})
      props)))

(defn sub-entity
  "Registers a subscription that returns a domain entity as a hashmap.
  id-attr for the entity, the entity subscription name (fq kw) a seq of dependent subscription props"
  [<sub sub-fn datasource id-attr plain-props join-props full-props attr-kw->sub-fn]
  ;; (log/info "IN SUB ENTITY!!")
  ;(log/debug "join props " join-props)
  ;(log/debug "join props " (set join-props))
  (let [join-props-set  (set join-props)
        plain-props-set (set plain-props)]
    (fn sub-entity_ [app args]
      ;(log/info "IN SUB ENTITY!!" id-attr)
      ;(log/debug "in sub-entity. id attr" id-attr)
      (assert app "Missing app to subscription")

      (if (some? (get args query-key))
        (let [props->ast           (eql-by-key (get args query-key))
              props-in-query       (keys (dissoc props->ast '*))
              plain-props-in-query (filter plain-props-set props-in-query)
              join-props-in-query  (filter join-props-set props-in-query)
              query                (get args query-key)
              star-query?          (some? (get props->ast '*))]
          ;(log/debug "\n\n------ENTITY sub have query" (pr-str query))
          ;(log/debug "JOIN PROPS IN QUERY" (vec join-props-in-query))
          (make-reaction
            (fn []
              (let [all-props (if star-query? (get-all-props-shallow datasource app id-attr full-props props->ast args) nil)
                    output    (if (or (nil? query) (= query '[*]))
                                (proto/-entity datasource app id-attr args)
                                (persistent!
                                  (reduce (fn [acc prop]
                                            (when-not (get attr-kw->sub-fn prop)
                                              (throw (error "Missing subscription for entity prop: " prop)))

                                            (let [prop-sub-fn (get attr-kw->sub-fn prop)
                                                  args'       (assoc args
                                                                ;; to implement recursive queries
                                                                ::parent-query query
                                                                query-key (:query (props->ast prop)))
                                                  output      (if <sub (<sub app [prop-sub-fn args']) (prop-sub-fn app args'))]
                                              (cond-> acc
                                                (not= missing-val output)
                                                (assoc! prop output))))
                                    ;; Here we pull the plain props from the entity directly as this can
                                    ;; be costly to invoke a subscription just for a simple get call.
                                    (get-all-props-shallow datasource app id-attr plain-props-in-query props->ast args)
                                    join-props-in-query)))
                    output    (if all-props (persistent! (reduce-kv (fn [acc k v] (assoc! acc k v)) all-props output)) output)]
                output))))
        (make-reaction (fn []
                         (persistent!
                           (reduce (fn [acc prop]
                                     (let [prop-sub-fn (get attr-kw->sub-fn prop)
                                           output      (if <sub (<sub app [prop-sub-fn args]) (prop-sub-fn app args))]
                                       (cond-> acc
                                         (not= output missing-val)
                                         (assoc! prop output))))
                             (get-all-props-shallow datasource app id-attr plain-props {} args)
                             join-props))))))))

(defn sub-plain-join
  [<sub datasource id-attr join-prop join-component-sub join-id-attr]
  (fn [db_ args]
    (make-reaction
      (fn []
        (missing-id-check! id-attr join-prop args)
        (let [refs    (not-empty (proto/-attr datasource db_ id-attr join-prop args))
              to-one? (some? (proto/-entity datasource db_ join-id-attr (assoc args join-id-attr (proto/-ref->id datasource refs))))
              query   (get args query-key)]
          (cond
            to-one?
            (<sub db_ [join-component-sub (assoc args join-id-attr (proto/-ref->id datasource refs))])

            refs
            (cond->> refs query
              (into []
                (comp
                  (map (fn [join-ref]
                         (<sub db_ [join-component-sub (assoc args join-id-attr (proto/-ref->id datasource join-ref))])))
                  (filter seq))))
            :else missing-val))))))

(defn union-query->branch-map*
  "Takes a union join query and returns a map of keyword of the branches of the join to the query for that branch."
  [join-prop union-join-q]
  (let [ast          (:children (eql/query->ast union-join-q))
        union-parent (first (filter (fn [{:keys [dispatch-key]}] (= dispatch-key join-prop)) ast))
        union-nodes  (-> union-parent :children first :children)]
    (reduce (fn [acc {:keys [union-key query]}] (assoc acc union-key query)) {} union-nodes)))

(def union-query->branch-map (memoize union-query->branch-map*))

(defn sub-union-join
  [<sub datasource id-attr join-prop join-component-sub]
  (fn [app args]
    (make-reaction
      (fn []
        ;(log/debug "\n--------------------------IN UNION join: " join-prop "args: " args)
        (missing-id-check! id-attr join-prop args)

        (let [refs                 (proto/-attr datasource app id-attr join-prop args)
              ref-attr             (proto/-ref->attribute datasource refs)
              to-one?              (some? (proto/-entity datasource app ref-attr (assoc args ref-attr (proto/-ref->id datasource refs))))
              query                (get args query-key)
              union-branch-map     (union-query->branch-map join-prop (::parent-query args))
              branch-keys-in-query (set (keys union-branch-map))]
          (cond
            to-one?
            (let [ref-entity (proto/-entity datasource app ref-attr (assoc args ref-attr (proto/-ref->id datasource refs)))
                  ;_          (log/debug "union ref-entity " ref-entity)
                  ;_          (log/debug "union (join-component-sub ref-entity) " (join-component-sub ref-entity))
                  [id-attr id-val join-sub] (join-component-sub ref-entity)]
              (<sub app [join-sub (assoc args id-attr id-val, query-key (union-branch-map id-attr))]))

            ;; to-many
            refs
            (if query
              (let [ref-entities (map (fn [ref]
                                        (let [ref-id-attr (proto/-ref->attribute datasource ref)]
                                          (proto/-entity datasource app ref-id-attr (assoc args ref-id-attr (proto/-ref->id datasource ref)))))
                                   refs)]
                (->> ref-entities
                  (filter (fn [ref-entity] (let [[id-attr] (join-component-sub ref-entity)]
                                             (contains? branch-keys-in-query id-attr))))
                  (mapv (fn [ref-entity]
                          (let [[id-attr id-val join-sub] (join-component-sub ref-entity)]
                            (<sub app [join-sub (assoc args id-attr id-val query-key (union-branch-map id-attr))]))))))
              (mapv (fn [ref]
                      (let [ref-id-attr (proto/-ref->attribute datasource ref)
                            ref-entity  (proto/-entity datasource app ref-id-attr (assoc args ref-id-attr (proto/-ref->id datasource ref)))
                            [id-attr id-val join-sub] (join-component-sub ref-entity)]
                        (<sub app [join-sub (assoc args id-attr id-val)])))
                    refs))

            :else (throw (error "Union Invalid join: for join prop " join-prop, " value: " refs))))))))

(def set-conj (fnil conj #{}))
(def inc0 (fnil inc 0))

(defn sub-recur-join
  [<sub datasource id-attr recur-prop recur-sub-fn_]
  (fn [app {::keys [entity-history parent-query] :as args}]
    (make-reaction
      (fn []
        (missing-id-check! id-attr recur-prop args)

        ;(log/debug "\n=====================IN RECUR JOIN FOR PROP: " recur-prop " entity id: " (get args id-attr))
        ;(log/debug "        entity history: " entity-history)
        (let [entity-sub          @recur-sub-fn_
              entity              (proto/-entity datasource app id-attr args)
              entity-db-id        (proto/-ref->id datasource entity)
              refs                (proto/-attr datasource app id-attr recur-prop args)
              ;; it is a to-one join if the attribute's value can successfully be used to lookup an entity in the DB.
              to-one?             (some? (proto/-entity datasource app id-attr (assoc args id-attr (proto/-ref->id datasource refs))))
              sub-query           (get args query-key)
              seen-entity-id?     (contains? entity-history entity-db-id)
              ;_                   (log/debug "ENTITY Id : " entity-db-id)
              ;_                   (log/debug "ENTITY : " entity)
              ;_                   (log/debug "ENTITY DB ID: " entity-db-id)
              ;; when entity-id and lookup-entity-id are the same then we want to get a db id somehow
              ;; they should not be the same?
              ;; maybe add another fn to the protocol
              ;; parse the recursive join arguments
              [recur-query recur-value walk-fn xform-fn]
              (when (and sub-query parent-query)
                (let [by-key       (eql-by-key parent-query)
                      recur-map    (get by-key recur-prop)
                      recur-value  (:query recur-map)
                      walk-fn-sym  (get-in recur-map [:params walk-fn-key])
                      xform-fn-sym (get-in recur-map [:params xform-fn-key])]
                  (cond
                    ;; plain unbounded recursion no logic
                    (and (nil? walk-fn-sym) (= recur-value '...))
                    (let [xform-fn (get args xform-fn-sym)]

                      (when (and xform-fn-sym (not (ifn? xform-fn)))
                        (throw (error "Missing function implementation in args map for transformation function symbol " xform-fn-sym
                                 " for id attribute: " id-attr " recursion attribute: " recur-prop)))

                      (if seen-entity-id?
                        [(vec (mapcat eql/ast->query (vals (dissoc by-key recur-prop)))) recur-value nil (or xform-fn identity)]
                        [parent-query recur-value nil (or xform-fn identity)]))

                    ;; Walking recursion
                    (and walk-fn-sym (= recur-value '...))
                    (let [walk-fn  (get args walk-fn-sym)
                          xform-fn (get args xform-fn-sym)]

                      (when (nil? walk-fn)
                        (throw (error "Missing function implementation in args map for walk function symbol " walk-fn-sym
                                 " for id attribute: " id-attr " recursion attribute: " recur-prop)))

                      (when (and xform-fn-sym (not (ifn? xform-fn)))
                        (throw (error "Missing function implementation in args map for transformation function symbol " xform-fn-sym
                                 " for id attribute: " id-attr " recursion attribute: " recur-prop)))

                      (when (and walk-fn (not (ifn? walk-fn)))
                        (throw (error "Walk function callback is not a function for attr" id-attr " recur prop: " recur-prop " received: " walk-fn)))

                      [parent-query recur-value walk-fn (or xform-fn identity)])

                    ;; bounded recursion
                    (pos-int? recur-value)
                    (let [by-key       (eql-by-key parent-query)
                          recur-map    (get by-key recur-prop)
                          recur-value  (:query recur-map)
                          xform-fn-sym (get-in recur-map [:params xform-fn-key])
                          xform-fn     (get args xform-fn-sym)]
                      [(ast-by-key->query (update-in by-key [recur-prop :query] dec)) recur-value nil (or xform-fn identity)]))))

              self-join?          (= (proto/-ref->id datasource refs) entity-db-id)
              infinite-self-join? (and recur-query self-join? (= recur-value '...))]

          ;(log/debug "recur-query: " (pr-str recur-query))

          ; Logic to implement recursion
          ;=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

          ;; how this works - we have an entity map from the db
          ;; for the recursion point we lookup the value in the db which will be normalized
          ;; the task is to determine given that normalized value is it a single id or is it a to-many id?

          ;--------------------------------------------------------------------------------
          ;; so if you take the ref and call entity on it and get back a map then you know it is to-one

          ;; if you don't get back anything or there is an error AND it is a collection/vector
          ;; then it is a to-many, and treat it as such

          (cond
            ;; pointer was nil
            (and recur-query (not refs)) missing-val

            ;; self cycle
            infinite-self-join? refs

            walk-fn
            (let [recur-output (walk-fn entity)]
              (cond
                (map? recur-output)
                (let [{:keys [expand stop]} recur-output]
                  (when-not (or
                              (contains? recur-output :expand)
                              (contains? recur-output :stop))
                    (error "Your walk function returned a map, but did not provide :expand or :stop keys."))
                  (let [refs-to-expand expand
                        join-ref       (proto/-entity datasource app id-attr (assoc args id-attr expand))
                        to-one?        (some? join-ref)]
                    (cond
                      to-one?
                      (let [ref-id (proto/-ref->id datasource refs-to-expand)
                            args'  (-> args
                                     (update ::depth inc0)
                                     (update ::entity-history set-conj entity-db-id)
                                     (assoc query-key recur-query, id-attr ref-id))]
                        (if seen-entity-id?
                          ;; cycle
                          (-> (<sub app [entity-sub args'])
                            (assoc recur-prop refs)
                            (xform-fn))
                          (xform-fn (<sub app [entity-sub args']))))

                      ;; to-many join
                      refs-to-expand
                      (if seen-entity-id?
                        refs
                        (into
                          (mapv (fn [join-ref]
                                  (xform-fn
                                    (<sub app [entity-sub
                                               (-> args
                                                 (update ::depth inc0)
                                                 (update ::entity-history set-conj entity-db-id)
                                                 (assoc query-key parent-query id-attr (proto/-ref->id datasource join-ref)))])))
                                refs-to-expand)
                          (when stop (mapv (fn [join-ref]
                                             (xform-fn
                                               (<sub app [entity-sub (-> args
                                                                       (update ::depth inc0)
                                                                       (update ::entity-history set-conj entity-db-id)
                                                                       (assoc query-key nil id-attr (proto/-ref->id datasource join-ref)))])))
                                           stop)))))))

                ;; some dbs support arbitrary collections as keys
                (coll? recur-output)
                (let [refs-to-recur recur-output
                      join-ref      (proto/-entity datasource app id-attr (assoc args id-attr refs-to-recur))
                      to-one?       (some? join-ref)]

                  (if to-one?
                    (let [ref-id (proto/-ref->id datasource refs-to-recur)]
                      (if seen-entity-id?
                        ;; cycle
                        (->
                          (<sub app [entity-sub (-> args
                                                  (update ::depth (fnil inc 0))
                                                  (update ::entity-history (fnil conj #{}) entity-db-id)
                                                  (assoc query-key recur-query, id-attr ref-id))])
                          (assoc recur-prop refs)
                          (xform-fn))

                        (xform-fn
                          (<sub app [entity-sub (-> args
                                                  (update ::depth (fnil inc 0))
                                                  (update ::entity-history (fnil conj #{}) entity-db-id)
                                                  (assoc query-key recur-query, id-attr ref-id))]))))

                    ;; to-many join
                    (if seen-entity-id?
                      refs
                      (mapv (fn [join-ref]
                              (xform-fn
                                (<sub app [entity-sub (-> args
                                                        (update ::depth (fnil inc 0))
                                                        (update ::entity-history (fnil conj #{}) entity-db-id)
                                                        (assoc query-key parent-query id-attr (proto/-ref->id datasource join-ref)))])))
                            refs-to-recur))))

                (some? recur-output)
                (let [join-ref (proto/-entity datasource app id-attr (assoc args id-attr (proto/-ref->id datasource refs)))
                      to-one?  (some? join-ref)]
                  (if seen-entity-id? ;; cycle
                    refs
                    (if to-one?
                      (xform-fn
                        (<sub app [entity-sub (-> args
                                                (update ::depth (fnil inc 0))
                                                (update ::entity-history (fnil conj #{}) refs)
                                                (assoc query-key recur-query, id-attr refs))]))
                      ;; to-many
                      (mapv (fn [join-ref]
                              (xform-fn
                                (<sub app [entity-sub (-> args
                                                        (update ::entity-history (fnil conj #{}) entity-db-id)
                                                        (assoc query-key recur-query, id-attr (proto/-ref->id datasource join-ref)))])))
                            refs))))

                ;; stop walking
                :else refs))

            ;; to-one join
            (and recur-query to-one?)
            (if seen-entity-id?
              refs ;; cycle
              (xform-fn
                (<sub app [entity-sub
                           (-> args
                             (update ::depth (fnil inc 0))
                             (update ::entity-history (fnil conj #{}) entity-db-id)
                             (assoc query-key recur-query, id-attr (proto/-ref->id datasource refs)))])))

            ;; to-many join
            (and recur-query (not to-one?))
            (if seen-entity-id?
              refs
              (mapv (fn [join-ref]
                      (xform-fn
                        (<sub app [entity-sub (-> args
                                                (update ::entity-history (fnil conj #{}) entity-db-id)
                                                (assoc query-key recur-query id-attr (proto/-ref->id datasource join-ref)))])))
                    refs))

            ;; do not recur
            refs (vec refs)
            :else missing-val))))))


(defn create-component-subs
  "Creates a subscription function (and dependent subscription functions) that will fulfill the given fulcro component's query.
  The subscription name will by the fully qualified keyword or symbol returned from class->registry-key of the component.
  The component must have a name and so must any components in its query."
  [sub-name-kw <sub sub-fn datasource c join-subs-map]
  (when-not (class->registry-key c) (throw (error "Component name missing on component: " c)))
  (let [query           (get-query c)
        entity-sub-name (class->registry-key c)
        id-attr         (component-id-prop c)
        {:keys [props plain-joins union-joins recur-joins all-children]} (eql-query-keys-by-type query
                                                                           (fn [join-ast] (join-subs-map (:dispatch-key join-ast)))
                                                                           (fn [join-ast id-attr] (get (join-subs-map (:dispatch-key join-ast)) id-attr)))]
    (when (map? query) (throw (error "You do not have to register a union component: " c)))
    (when-not id-attr (throw (error "Component missing ident: " c)))
    (assert (every? #(fn? (get join-subs-map %)) (map first plain-joins)) (str "All joins must have a provided subscription " (pr-str (map first plain-joins))))
    (assert (every? (fn [[u]]
                      (and (map? (get join-subs-map u)) (every? fn? (vals (get join-subs-map u)))))
              union-joins) (str "All joins must have a provided subscription " (pr-str (map first union-joins))))
    (let [recur-join-fn_  (atom nil)
          prop-subs       (zipmap props (map (fn [p]
                                               (vary-meta
                                                 (proto/-attribute-subscription-fn datasource id-attr p)
                                                 assoc sub-name-kw p)) props))
          plain-join-subs (zipmap (map first plain-joins) (map (fn [[prop component-sub join-id-attr]]
                                                                 (vary-meta
                                                                   (sub-plain-join <sub datasource id-attr prop component-sub join-id-attr)
                                                                   assoc sub-name-kw prop)) plain-joins))
          union-join-subs (zipmap (map first union-joins) (map (fn [[prop component-sub]]
                                                                 (vary-meta
                                                                   (sub-union-join <sub datasource id-attr prop component-sub)
                                                                   assoc sub-name-kw prop)) union-joins))
          recur-join-subs (zipmap (map first recur-joins) (map (fn [[p]]
                                                                 (vary-meta
                                                                   (sub-recur-join <sub datasource id-attr p recur-join-fn_)
                                                                   assoc sub-name-kw p)) recur-joins))
          kw->sub-fn      (merge prop-subs plain-join-subs union-join-subs recur-join-subs)
          entity-sub      (vary-meta (sub-fn (sub-entity <sub sub-fn datasource id-attr (keys prop-subs)
                                               (set/union (set (map first plain-joins)) (set (map first union-joins)) (set (map first recur-joins)))
                                               all-children kw->sub-fn))
                            assoc ::component c, sub-name-kw entity-sub-name, ::id-attr id-attr)]
      (reset! recur-join-fn_ entity-sub)
      entity-sub)))

(defn register-component-subs!
  "Registers subscriptions that will fulfill the given fulcro component's query.
  The subscription name will by the fully qualified keyword or symbol returned from class->registry-key of the component.
  The component must have a name and so must any components in its query."
  [reg-sub-raw <sub datasource c]
  (when-not (class->registry-key c) (throw (error "Component name missing on component: " c)))
  (let [query           (get-query c)
        entity-sub      (class->registry-key c)
        id-attr         (component-id-prop c)
        {:keys [props plain-joins union-joins recur-joins all-children]} (eql-query-keys-by-type query
                                                                           (fn [join-ast]
                                                                             (-> join-ast :component class->registry-key))
                                                                           (fn [{:keys [children] :as _join-ast} id-attr]
                                                                             (let [union-key->entity (union-key->entity-sub (first children))]
                                                                               (id-attr union-key->entity))))
        join-props->sub (merge
                          (into {} (for [p props] [p p]))
                          (into {} (for [[p] plain-joins] [p p]))
                          (into {} (for [[p] union-joins] [p p]))
                          (into {} (for [[p] recur-joins] [p p])))]
    (when-not id-attr (throw (error "Component missing ident: " c)))
    (run! (fn [prop] (reg-sub-raw prop (proto/-attribute-subscription-fn datasource id-attr prop))) props)
    (run! (fn [[prop component-sub join-id-attr]] (reg-sub-raw prop (sub-plain-join <sub datasource id-attr prop component-sub join-id-attr))) plain-joins)
    (run! (fn [[prop component-sub]] (reg-sub-raw prop (sub-union-join <sub datasource id-attr prop component-sub))) union-joins)
    (run! (fn [[prop]] (reg-sub-raw prop (sub-recur-join <sub datasource id-attr prop (atom entity-sub)))) recur-joins)
    (reg-sub-raw entity-sub
      (vary-meta (sub-entity <sub nil datasource id-attr props
                   (set/union (set (map first plain-joins)) (set (map first union-joins)) (set (map first recur-joins)))
                   all-children join-props->sub)
        assoc ::id-attr id-attr))
    nil))
