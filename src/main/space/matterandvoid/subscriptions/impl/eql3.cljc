(ns space.matterandvoid.subscriptions.impl.eql3
  (:require
    [com.fulcrologic.fulcro.raw.components :as rc]
    [space.matterandvoid.subscriptions.impl.core :as subs :refer [reg-sub reg-sub-raw subscribe <sub]]
    [space.matterandvoid.subscriptions.impl.reagent-ratom :as r :refer [make-reaction]]
    [sc.api]
    [taoensso.encore :as enc]
    [edn-query-language.core :as eql]))

(subs/set-memoize-fn! identity)

(defn nc-union
  [{:keys [children] :as ast-node} {:keys [componentName ident] :as top-component-options}]
  (println "normalize uni")
  (let [component     (fn [& _args])
        component-map (into {}
                        (map (fn [{:keys [union-key component] :as c}]
                               [union-key component]) children))
        union-keys    (into #{} (map :union-key) children)
        component     (rc/configure-anonymous-component! component
                        (cond-> (with-meta
                                  (merge
                                    {:initial-state    (fn [& args] {})
                                     :fulcro/warnings? false}
                                    top-component-options
                                    {:query  (fn [& args]
                                               (enc/map-vals rc/get-query component-map))
                                     "props" {"fulcro$queryid" :anonymous}})
                                  {:query-id :anonymous})
                          (not ident) (assoc :ident
                                             (fn [this props]
                                               (when-let [k (union-keys props)]
                                                 [k (get props k)])))
                          componentName (assoc :componentName componentName)))]
    (assoc ast-node :component component)))

(defn nc
  "Wrap fulcro.raw.components/nc to be more uniform and require explicit options instead of implicit for normalizing
  components."
  [args]
  (assert (and (:name args) (keyword? (:name args))))
  (assert (and (:query args) (or (vector? (:query args)) (map? (:query args)))))
  (if (map? (:query args))
    (let [ast (-> (eql/query->ast [{:placeholder (:query args)}])
                :children first :children first)]
      (:component
        (nc-union ast
          (-> args
            (assoc :componentName (:name args)) (dissoc :query :name)))))

    (let [{:keys [ident]} args]
      (assert (and (:ident args) (or (keyword? (:ident args)) (fn? (:ident args)))))
      (rc/nc (:query args)
        (-> args
          (assoc :ident (if (keyword? ident) (fn [_ props] [ident (ident props)]) ident))
          (assoc :componentName (:name args))
          (dissoc :query :name))))))

(defn group-by-flat [f coll] (persistent! (reduce #(assoc! %1 (f %2) %2) (transient {}) coll)))

(def missing-val ::missing)

(defn eql-by-key [query] (group-by-flat :dispatch-key (:children (eql/query->ast query))))
(defn ast-by-key->query [k->ast] (vec (mapcat eql/ast->query (vals k->ast))))

(defn recur? [q] (or (= '... q) (pos-int? q)))
(defn error [& args] #?(:clj (Exception. ^String (apply str args)) :cljs (js/Error. (apply str args))))

(def db_ (r/atom {:comment/id {1 {:comment/id           1 :comment/text "FIRST COMMENT"
                                  :comment/sub-comments [[:comment/id 2]]}
                               2 {:comment/id 2 :comment/text "SECOND COMMENT"}
                               3 {:comment/id 3 :comment/text "THIRD COMMENT"}}
                  :list/id    {1 {:list/id 1 :list/name "first list" :list/members [[:comment/id 3] [:todo/id 2]]}}
                  :todo/id    {1 {:todo/id      1 :todo/text "todo 1"
                                  :todo/comment [:comment/id 1]}
                               2 {:todo/id 2 :todo/text "todo 2"}}}))

(comment
  (let [union
        {:type     :union,
         :query    {:comment/id [:comment/id :comment/text {:comment/sub-comments '...}],
                    :todo/id    [:todo/id :todo/text {:todo/comment [:comment/id :comment/text]}]},
         :children [{:type      :union-entry,
                     :union-key :comment/id,
                     :query     [:comment/id :comment/text {:comment/sub-comments '...}],
                     :children  [{:type :prop, :dispatch-key :comment/id, :key :comment/id}
                                 {:type :prop, :dispatch-key :comment/text, :key :comment/text}
                                 {:type         :join,
                                  :dispatch-key :comment/sub-comments,
                                  :key          :comment/sub-comments,
                                  :query        '...}],
                     :component {:com.fulcrologic.fulcro.components/component-class? true,
                                 :fulcro$registryKey                                 nil,
                                 :displayName                                        "anonymous"}}
                    {:type      :union-entry,
                     :union-key :todo/id,
                     :query     [:todo/id :todo/text {:todo/comment [:comment/id :comment/text]}],
                     :children  [{:type :prop, :dispatch-key :todo/id, :key :todo/id}
                                 {:type :prop, :dispatch-key :todo/text, :key :todo/text}
                                 ],
                     :component {:com.fulcrologic.fulcro.components/component-class? true, :fulcro$registryKey nil, :displayName "anonymous"}}]}
        ]
    (reduce (fn [acc ast]
              (assoc acc (:union-key ast)
                         (rc/class->registry-key (:component ast)))
              ) {} (:children union)))
  )

(defn union-key->entity-sub [union-ast]
  (def u' union-ast)
  (reduce (fn [acc {:keys [union-key component]}]
            (let [reg-key (rc/class->registry-key component)]
              (when-not reg-key (throw (error "missing union component name for key: " union-key)))
              (assoc acc union-key reg-key)))
    {}
    (:children union-ast)))

(comment
  (into {} (map (juxt :union-key (comp rc/class->registry-key :component))
             (:children u')))
  (apply hash-map (map (juxt :union-key (comp rc/class->registry-key :component))
                    (:children u')))
  (keys u')
  (union-key->entity-sub u'))

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
                                      (let [union-key->entity (union-key->entity-sub (first children))
                                            _                 (println "union-key->entity " union-key->entity)
                                            union-key->component
                                                              [dispatch-key
                                                               (fn [kw]
                                                                 (println "union-key to comp args: " kw)
                                                                 (assert (keyword? kw))
                                                                 (let [the-sub (kw union-key->entity)]
                                                                   (println "UNION entity subscription: " the-sub)
                                                                   the-sub))

                                                               ;(fn [args-map]
                                                               ;  (println "union-key to comp args: " args-map)
                                                               ;  (sc.api/spy
                                                               ;    (let [the-sub
                                                               ;          (some union-key->entity (keys args-map))
                                                               ;          ;(reduce-kv
                                                               ;          ;  (fn [acc k entity-sub]
                                                               ;          ;    (if (contains? args-map k)
                                                               ;          ;      (reduced entity-sub) acc))
                                                               ;          ;  nil
                                                               ;          ;  union-key->entity)
                                                               ;          ]
                                                               ;      (println "UNION entity subscription: " the-sub)
                                                               ;      the-sub))
                                                               ;  )
                                                               ]]
                                        (println "union-key->component " union-key->component)
                                        union-key->component
                                        ))
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
(comment

  (sc.api/defsc)
  (eql-query-keys-by-type list-q)
  (meta (:comment/id (:list/members (last list-q))))
  )

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
    (fn [db_ args]
      (println "\n\nreg-sub-entity: " entity-kw)
      (println "id kw: " id-kw)
      (println "IN enttity " args)
      ;(when-not (get args id-kw) (throw (error "subscription " (pr-str entity-kw) " missing id argument: " args)))
      (if (::subs/query args)
        (let [props->ast (eql-by-key (::subs/query args))
              props'     (keys props->ast)]
          (println "HAVE QUERY: " props')
          (make-reaction
            (fn []
              (let [output
                    (reduce (fn [acc prop]
                              (comment (sc.api/defsc 274))
                              (sc.api/spy
                                (let [output
                                      (do
                                        (println "entity sub, sub-query for: " prop)
                                        (<sub db_ [prop (assoc args
                                                          ;; to implement recursive queries
                                                          ::subs/parent-query (::subs/query args)
                                                          ::subs/query (:query (props->ast prop)))]))]
                                  (cond-> acc
                                    (not= missing-val output)
                                    (assoc prop output)))))
                      {} props')]
                (println "output: " output)
                output))))
        (make-reaction
          (fn [] (reduce (fn [acc prop] (assoc acc prop (<sub db_ [prop args]))) {} props)))
        ))))

(defn reg-sub-union-entity
  "Registers a subscription that returns a domain entity as a hashmap.
  id-attr for the entity, the entity subscription name (fq kw) a seq of dependent subscription props"
  [entity-kw props]
  (reg-sub-raw entity-kw
    (fn [db_ args]
      (println "\n\nUNION reg-sub-union-entity: " entity-kw)
      (println "IN enttity " args)
      ;(when-not (get args id-kw) (throw (error "subscription " (pr-str entity-kw) " missing id argument: " args)))
      (if (::subs/query args)
        (let [props->ast (eql-by-key (::subs/query args))
              props'     (keys props->ast)]
          (println "HAVE QUERY: " props')
          (make-reaction
            (fn []
              (let [output
                    (reduce (fn [acc prop]
                              (let [output
                                    (do
                                      (println "entity sub, sub-query for: " prop)
                                      (<sub db_ [prop (assoc args
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
          (fn [] (reduce (fn [acc prop] (assoc acc prop (<sub db_ [prop args]))) {} props)))
        ))))

;; todo need to test to-one and to-many joins
;; todo to-one recur and to-many
;; todo need to test to-one and to-many recur joins

(defn reg-sub-plain-join
  "Takes two keywords: id attribute and property attribute, registers a layer 2 subscription using the id to lookup the
  entity and extract the property."
  [id-attr join-prop join-component-sub]
  (reg-sub-raw join-prop
    (fn [db_ args]
      (make-reaction
        (fn []
          (println " in plain join prop: " join-prop)
          (println " in plain join join-comp sub: " join-component-sub)
          (println " in plain join args: " args)
          (if-let [entity-id (get args id-attr)]
            (let [entity    (get-in @db_ [id-attr entity-id])
                  rels      (not-empty (get entity join-prop))
                  query     (::subs/query args)
                  query-ast (eql/query->ast query)]
              (def query' query-ast)
              (cond
                (eql/ident? rels)
                (do
                  (println "HAVE single ident: " rels)
                  (println "sub: " [join-component-sub (apply assoc args rels)])
                  (if (and (map? query) (fn? join-component-sub))
                    ;;union
                    (<sub db_ [(join-component-sub args) (apply assoc args rels)])
                    (<sub db_ [join-component-sub (apply assoc args rels)])))
                rels
                (do
                  (println "HAVE many idents: " rels)
                  (if (::subs/query args)
                    (mapv (fn [[id v]]
                            ;; handle union
                            (<sub db_ [join-component-sub (assoc args id v)])) rels)
                    rels))

                :else (throw (error "Plain join Invalid join: for join prop " join-prop, " value: " rels))))
            (throw (error "Missing id attr: " id-attr " in args map passed to subscription: " join-prop))))))))

(defn union-query->branch-map
  "Takes a union join query and returns a map of keyword of the branches of the join to the query for that branch."
  [join-prop union-join-q]
  (let [ast          (:children (eql/query->ast union-join-q))
        union-parent (first (filter (fn [{:keys [dispatch-key]}] (= dispatch-key :list/members)) ast))
        union-nodes  (-> union-parent :children first :children)]

    (reduce (fn [acc {:keys [union-key query]}] (assoc acc union-key query)) {} union-nodes)))

(comment
  (union-query->branch-map
    :list/members
    [:other/thing :list/name #:list{:members {:comment/id [:comment/id :comment/text],
                                              :todo/id    [:todo/id :todo/text]}}])
  (union-query->branch-map
    :list/members
    [#:list{:members {:comment/id [:comment/id :comment/text],
                      :todo/id    [:todo/id :todo/text]}}]
    )
  )

(defn reg-sub-union-join
  "Takes two keywords: id attribute and property attribute, registers a layer 2 subscription using the id to lookup the
  entity and extract the property."
  [id-attr join-prop join-component-sub]
  (reg-sub-raw join-prop
    (fn [db_ args]
      (make-reaction
        (fn []
          (println " \n----in union join prop: " join-prop, "id attr: " id-attr)
          (println " in union join args: " args)
          (if-let [entity-id (get args id-attr)]
            (do
              (println "UNION have entity id: " entity-id)
              (let [entity           (get-in @db_ [id-attr entity-id])
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
                    (println "HAVE single ident: " rels)
                    (println "sub: " [join-component-sub (apply assoc args rels)])
                    (<sub db_ [(join-component-sub args)
                               (assoc args
                                 (first rels) (second rels)
                                 ::subs/query (union-branch-map (first rels)))]))
                  rels
                  (do
                    (println "HAVE many idents: " rels)
                    (if (::subs/query args)
                      (do
                        (println "handling idents")
                        (mapv (fn [[id v]]
                                (println "getting sub: " id ", v: " v)
                                (println "join: " (join-component-sub id))
                                (println "union branch map : " union-branch-map)
                                (let [args'
                                      (assoc args id v
                                                  ::subs/query (union-branch-map id))
                                      ]
                                  (println "new args: " args')
                                  ;[(join-component-sub id) args']
                                  (<sub db_ [(join-component-sub id) args'])
                                  )
                                ) rels))
                      rels))

                  :else (throw (error "Union Invalid join: for join prop " join-prop, " value: " rels)))))
            (throw (error "Missing id attr: " id-attr " in args map passed to subscription: " join-prop))))))))
(comment
  (<sub db_ [::list {:list/id 1 ::subs/query [:list/name :list/members]}])
  (<sub db_ [::list {:list/id 1 ::subs/query [:list/name {:list/members {:comment/id [:comment/id :comment/text]
                                                                         :todo/id    [:todo/id :todo/text]}}]}])
  )

(defn reg-sub-recur-join
  [id-attr recur-prop entity-sub]
  (reg-sub-raw recur-prop
    (fn [db_ {::subs/keys [recur-depth max-recur-depth parent-query]
              :or         {recur-depth 0}
              :as         args}]
      (make-reaction
        (fn []
          (println "sub recur-join children: " recur-prop ", " args)
          (if-let [entity-id (get args id-attr)]
            (let [refs        (seq (filter some? (get-in @db_ [id-attr entity-id recur-prop])))
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
                          (mapv (fn [[_ id]] (<sub db_ [entity-sub (assoc args
                                                                     ;:recur/depth (inc recur-depth) :recur/max-depth max-recur-depth
                                                                     ::subs/query recur-query
                                                                     id-attr id)]))
                                refs))
                        ;; do not recur
                        refs (do (println "NOT RECUR") (vec refs))
                        :else missing-val)]
                r))
            (throw (error "Missing id attr: " id-attr " in args map passed to subscription: " recur-prop))))))))

(comment (sc.api/defsc 25))

(comment
  (get-in @db_ [:comment/id 2])
  (<sub db_ [::comment
             {:comment/id                                               2,
              :space.matterandvoid.subscriptions.impl.core/query        [:comment/text {:comment/sub-comments '...}]
              :space.matterandvoid.subscriptions.impl.core/parent-query [:comment/text {:comment/sub-comments '...}]}
             ])
  (<sub db_ [::comment {:comment/id 1 ::subs/query [:comment/id #_:comment/text {:comment/sub-comments 1}]}]))

(comment
  (let [recur-prop   :comment/sub-comments
        parent-query [:comment/id #:comment{:sub-comments 1}]
        by-key       (eql-by-key parent-query)
        recur-value  (:query (get by-key recur-prop))]
    (eql/ast->query)
    ))

(defn component-id-prop [c] (first (rc/get-ident c {})))

(defn component->reg-subs!
  "Registers subscriptions that will fulfill the given fulcro component's query."
  [c]
  (when-not (rc/class->registry-key c)
    (throw (error "Component name missing on component: " c)))
  (let [query      (rc/get-query c)
        entity-sub (rc/class->registry-key c)
        id-attr    (component-id-prop c)
        {:keys [props plain-joins union-joins recur-joins all-children]} (eql-query-keys-by-type query)]
    ;; i _think_ that if id-attr is nil that means it's a union
    ;; in that case other subs are handling everything, so you maybe just skip those
    (when id-attr
      (do
        (run! (fn [p] (reg-sub-prop id-attr p)) props)
        (run! (fn [[p component-sub]] (reg-sub-plain-join id-attr p component-sub)) plain-joins)
        (run! (fn [[p component-sub]] (reg-sub-union-join id-attr p component-sub)) union-joins)
        (run! (fn [[p]] (reg-sub-recur-join id-attr p entity-sub)) recur-joins)
        (reg-sub-entity id-attr entity-sub all-children))
      ;do
      ;(throw (error "mising id" c))
      ;(reg-sub-union-entity entity-sub all-children)
      )
    nil))

(comment
  (<sub db_ [::list {:list/id 1 ::subs/query [:list/name {:list/members {:comment/id [:comment/id :comment/text]
                                                                         :todo/id    [:todo/id :todo/text]}}]}]))
; components
;=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

;; components like this are the input (created by a dev etc):
;; these are your domain entities

(def comment-comp (nc {:query [:comment/id :comment/text] :name ::comment :ident :comment/id}))
(def comment-q (rc/get-query comment-comp))
(rc/class->registry-key comment-comp)
(rc/component-name comment-comp)

(def comment-recur-comp (nc {:query [:comment/id :comment/text {:comment/sub-comments '...}] :name ::comment :ident :comment/id}))
(def comment-r-q (rc/get-query comment-recur-comp))

(def todo-comp (nc {:query [:todo/id :todo/text {:todo/comment (rc/get-query comment-comp)}] :name ::todo :ident :todo/id}))
(def todo-q (rc/get-query todo-comp))
(def list-member-comp (nc {#_#_:ident (fn [_ props]
                                        (let [ks (set (keys props))] (cond
                                                                       (contains? ks :comment/id) :comment/id
                                                                       (contains? ks :todo/id) :todo/id)))
                           :query {:comment/id (rc/get-query comment-recur-comp) :todo/id todo-q}
                           :name  ::list-member}))


(def list-member-q (rc/get-query list-member-comp))
(def list-comp (nc {:ident :list/id
                    :name  ::list
                    :query [:list/id :list/name
                            {:list/members (rc/get-query list-member-comp)}]}))
(def list-q (rc/get-query list-comp))
(comment
  (<sub db_ [::list {:list/id 1 ::subs/query [#_:list/name {:list/members {:comment/id [:comment/id :comment/text]
                                                                           :todo/id    [:todo/id :todo/text]}}]}])
  )

(comment
  (meta (:comment/id (rc/get-query (nc {:name ::helo :query {:comment/id (rc/get-query comment-recur-comp) :todo/id todo-q}}))))
  (meta (:comment/id (rc/get-query list-member-comp)))
  (rc/class->registry-key (:component (meta (:todo/id (rc/get-query list-member-comp)))))


  (-> (rc/get-query list-comp) :list/members)

  (rc/get-query list-member-comp)

  (let [ast (-> (eql/query->ast [{:placeholder {:comment/id (rc/get-query comment-recur-comp) :todo/id todo-q}}])
              :children first :children first)]
    (into {}
      (map (fn [{:keys [union-key component] :as c}]
             [union-key component])
        (:children ast)))
    )


  (eql-query-keys-by-type list-q)

  (eql/query->ast list-q)
  (eql/query->ast {:comment/id comment-r-q :todo/id todo-q})
  (as-> (-> list-q (eql/query->ast) :children) X
    (group-by :type X)))
(let
  [query-nodes       (-> list-q (eql/query->ast) :children)
   {unions :union props :prop joins :join} (group-by :type query-nodes)
   [recur-joins plain-joins] (split-with (comp recur? :query) joins)
   set-keys          #(->> % (map :dispatch-key) set)
   plain-joins       (set (map (juxt :dispatch-key (comp rc/class->registry-key :component)) plain-joins))
   missing-join-keys (filter (comp nil? second) plain-joins)]
  )

(rc/get-ident comment-comp {:comment/id 5})
(rc/get-ident comment-comp {:comment/id 5 :comment/hi "hi"})
(rc/get-ident todo-comp {:todo/id 5 :todo/comment {:comment/text "hi"}})

(eql-query-keys-by-type comment-q)
(eql-query-keys-by-type comment-r-q)
(eql-query-keys-by-type todo-q)

; register subs
;=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-
(component->reg-subs! comment-recur-comp)
(component->reg-subs! todo-comp)
(component->reg-subs! list-member-comp)
(component->reg-subs! list-comp)

; queries
;=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(comment
  (<sub db_ [:list/id {:list/id 1}])
  (<sub db_ [::list {:list/id 1 ::subs/query [#_:list/name {:list/members {:comment/id [:comment/id :comment/text]
                                                                           :todo/id    [:todo/id :todo/text]}}]}])
  (<sub db_ [:comment/id {:comment/id 1}])
  (<sub db_ [:comment/text {:comment/id 1}])
  (<sub db_ [:comment/sub-comments {:comment/id 1 ::subs/max-recur-depth 1}])
  (<sub db_ [::comment {:comment/id 1 ::subs/query [:comment/id {:comment/sub-comments 1}]}])

  (<sub db_ [:todo/text {:todo/id 1}])
  (<sub db_ [:todo/comment {:todo/id 1}])
  (<sub db_ [::todo {:todo/id 1}])
  (<sub db_ [:comment/id {:comment/id 1}])
  ;; now i'm seeing it - this is how you can get the control of what to lookup as the tree of subs is realized:
  (<sub db_ [::todo {:todo/id     1
                     ::subs/query [:todo/id
                                   {:todo/comment [:comment/id
                                                   :comment/text
                                                   {:comment/sub-comments 2}]}]}])

  ;; todo:
  ;; to-one and to-many joins for plain and recur
  ;; union query support
  ;; sub-select the part of the query by passing them as args
  )


;; idea for recursion
;; you could put a function at the recursion point and that would let you use that predicate to determine if you should
;; continue traversing or not
;; EQL will not parse this, you can vendor in eql though and change it to support that
