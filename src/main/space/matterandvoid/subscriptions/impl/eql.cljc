(ns space.matterandvoid.subscriptions.impl.eql
  (:require
    [com.fulcrologic.fulcro.raw.components :as rc]
    [space.matterandvoid.subscriptions.impl.core :as subs :refer [reg-sub reg-sub-raw subscribe <sub]]
    [space.matterandvoid.subscriptions.impl.reagent-ratom :as r :refer [make-reaction]]
    [edn-query-language.core :as eql]))


;; here is the db as it would be in fulcro:
(def my-db2
  {:block/id {
              #uuid"44ddbac9-5097-4980-a6fe-287448f98094"
              #:block{:id #uuid"44ddbac9-5097-4980-a6fe-287448f98094", :type :todo, :properties {:content "task1 to do"}}
              #uuid"fe6e9d31-3cde-4683-91ab-e558b0fc7aa3"
              #:block{:id #uuid"fe6e9d31-3cde-4683-91ab-e558b0fc7aa3" :type :todo, :properties {:content "task2 to do"}}
              #uuid"74b41616-c744-4054-88c4-cf2354fc7938"
              #:block{:id #uuid"74b41616-c744-4054-88c4-cf2354fc7938" :type :todo, :properties {:content "task3 to do"}}

              #uuid"a21b0958-8ee5-48d2-aeca-209d6dc47587"
              {:block/id         #uuid"a21b0958-8ee5-48d2-aeca-209d6dc47587"
               :block/type       :page
               :block/properties {:title "my todo list"}
               :block/children   [[:block/id #uuid "44ddbac9-5097-4980-a6fe-287448f98094"]
                                  [:block/id #uuid "fe6e9d31-3cde-4683-91ab-e558b0fc7aa3"]
                                  [:block/id #uuid "74b41616-c744-4054-88c4-cf2354fc7938"]]}
              #uuid"fcecdd78-2a27-4e3e-9410-5ea5a45b8bda"
              {:block/id         #uuid"fcecdd78-2a27-4e3e-9410-5ea5a45b8bda"
               :block/type       :text
               :block/properties {:content "leaf 1"}}

              #uuid"67b62a83-d763-4dce-9cd6-f488a149ac5a"
              {:block/id         #uuid"67b62a83-d763-4dce-9cd6-f488a149ac5a"
               :block/type       :text
               :block/properties {:content "leaf 2"}}

              #uuid"a2107c25-5c72-48c6-a1fa-bad341ca631b"
              {:block/id       #uuid"a2107c25-5c72-48c6-a1fa-bad341ca631b"
               :block/type     :bullet-list
               :block/children [[:block/id #uuid"fcecdd78-2a27-4e3e-9410-5ea5a45b8bda"]
                                [:block/id #uuid"67b62a83-d763-4dce-9cd6-f488a149ac5a"]
                                [:block/id #uuid"7b85881e-40d4-4727-921e-183c9012c490"]]}
              #uuid"88162056-dce8-4142-a85d-a0570eaf8e40",
              {:block/id         #uuid"88162056-dce8-4142-a85d-a0570eaf8e40",
               :block/type       :text,
               :block/properties {:content "leaf 4"}}
              #uuid"7b85881e-40d4-4727-921e-183c9012c490"
              {:block/id         #uuid"7b85881e-40d4-4727-921e-183c9012c490",
               :block/type       :container,
               :block/properties {},
               :block/children   [
                                  [:block/id #uuid"2079a8b4-e939-4ebb-a88b-deb8a856d7bb"]
                                  [:block/id #uuid"88162056-dce8-4142-a85d-a0570eaf8e40"]]}
              #uuid"2079a8b4-e939-4ebb-a88b-deb8a856d7bb"
              {:block/id         #uuid"2079a8b4-e939-4ebb-a88b-deb8a856d7bb"
               :block/type       :text
               :block/properties {:content "leaf 3"}}
              #uuid"900e63a5-0ad2-4710-aee2-84d6aeae1339"
              {:block/id         #uuid"900e63a5-0ad2-4710-aee2-84d6aeae1339",
               :block/type       :page,
               :block/properties {:title "my home page"},
               :block/children   [[:block/id #uuid"a21b0958-8ee5-48d2-aeca-209d6dc47587"]
                                  [:block/id #uuid"a2107c25-5c72-48c6-a1fa-bad341ca631b"]]}}})

" so now the goal is to get a tree of data
 by saying: (<sub [::block.model/block ])"


;; Problem statement:
;; I have a fulcro query and am using subscriptions, I want to invoke (subscribe) with the keys of that query without
;; having to write reg-subs manually.
;; This would a way to implement the equivalent of db->tree but with subscriptions only and a normalized db.
;; My intuition is that it will be more performant with subscriptions but first I have to implement it, then we can compare.

;; essentially I want the normalized layers of the fulcro db to have subscriptions created automatically, because there
;; should be a one-to-one mapping for the implementation of them.

;; squinting a bit, subscriptions are a query layer/language (language needs a means of combination and means of abstraction,
;; not sure if we have both)

;; the idea of this ns is to take an EQL query (only the reading subset: used by fulcro components)
;; - props
;; - joins - with recursion support
;; - unions - with recursion support ?

;; from the EQL we then invoke reg-sub dynamically using the keywords of the query as the sub names and generic function
;; implementations for each type (prop, join (with+without recursion), union)


;Example:

(defn recur? [q] (or (= '... q) (pos-int? q)))
(defn error [& args] #?(:clj  (Exception. ^String (apply str args))
                        :cljs (js/Error. (apply str args))))

(defn eql-query-keys-by-type
  [query]
  (let [query-nodes       (-> query (eql/query->ast) :children)
        {unions :union props :prop joins :join} (group-by :type query-nodes)
        [recur-joins plain-joins] (split-with (comp recur? :query) joins)
        set-keys          #(->> % (map :dispatch-key) set)
        plain-joins       (set (map (juxt :dispatch-key (comp rc/class->registry-key :component)) plain-joins))
        missing-join-keys (filter (comp nil? second) plain-joins)]
    (when (seq missing-join-keys)
      (throw (error "All join properties must have a component name. Props missing names: " (mapv first missing-join-keys))))
    {:all-children      (reduce into [] [(set-keys unions) (set-keys joins) (set-keys props)])
     :unions            (set-keys unions)
     :joins             (set-keys joins)
     :props             (set-keys props)
     :recur-joins       (set (map (juxt :dispatch-key :query) recur-joins))
     :missing-join-keys missing-join-keys
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

(def db_ (r/atom {:comment/id {1 {:comment/id           1 :comment/text "FIRST COMMENT"
                                  :comment/sub-comments [[:comment/id 2]]}
                               2 {:comment/id 2 :comment/text "SECOND COMMENT"}}
                  :todo/id    {1 {:todo/id      1 :todo/text "hi"
                                  :todo/comment [:comment/id 1]}}}))

(defn reg-sub-entity
  "Registers a subscription that returns a domain entity as a hashmap.
  id-attr for the entity, the entity subscription name (fq kw) a seq of dependent subscription props"
  [id-kw entity-kw props]
  (reg-sub-raw entity-kw
    (fn [db_ args]
      (println "IN enttity " args)
      (when-not (get args id-kw) (throw (error "subscription " (pr-str entity-kw) " missing id argument: " args)))
      (make-reaction
        (fn [] (reduce (fn [acc prop] (assoc acc prop (<sub db_ [prop args]))) {} props))))))

;; todo need to test to-one and to-many joins

(defn reg-sub-plain-join
  "Takes two keywords: id attribute and property attribute, registers a layer 2 subscription using the id to lookup the
  entity and extract the property."
  [id-attr join-prop join-component-sub]
  (reg-sub-raw join-prop
    (fn [db_ args]
      (make-reaction
        (fn []
          (println " in plain join")
          (if-let [entity-id (get args id-attr)]
            (let [entity (get-in @db_ [id-attr entity-id])
                  rels   (not-empty (get entity join-prop))]
              (cond
                (eql/ident? rels)
                (let [join-ref (apply hash-map rels)]
                  (<sub db_ [join-component-sub join-ref]))
                rels
                (mapv #(<sub db_ [join-component-sub %]) rels)

                :else (throw (error "Invalid join: for join prop " join-prop, " value: " rels))))
            (throw (error "Missing id attr: " id-attr " in args map passed to subscription: " join-prop))))))))

;; todo to-one recur and to-many
;; todo need to test to-one and to-many recur joins

(defn reg-sub-recur-join
  [id-attr recur-prop entity-sub]
  (reg-sub-raw recur-prop
    (fn [db_ {::subs/keys [recur-depth max-recur-depth]
              :or         {recur-depth 0}
              :as         args}]
      (make-reaction
        (fn []
          (println "sub-raw children: " recur-prop ", " args)

          (if-let [entity-id (get args id-attr)]
            (let [refs (seq (filter some? (get-in @db_ [id-attr entity-id recur-prop])))]
              (println "---------------Refs: " refs)
              (cond
                (and refs max-recur-depth recur-depth (> max-recur-depth recur-depth))
                (do
                  (println "RECUR")
                  (map (fn [[_ id]] (<sub db_ [entity-sub (assoc args
                                                            :recur/depth (inc recur-depth) :recur/max-depth max-recur-depth
                                                            id-attr id)]))
                    refs))
                ;; do not recur
                refs (do (println "NOT RECUR") refs)
                :else nil))
            (throw (error "Missing id attr: " id-attr " in args map passed to subscription: " recur-prop))))))))

(defn component-id-prop [c] (first (rc/get-ident c {})))

(defn component->reg-subs [c]
  (when-not (rc/class->registry-key c)
    (throw (error "Component name missing on component: " c)))
  (let [query      (rc/get-query c)
        entity-sub (rc/class->registry-key c)
        id-attr    (component-id-prop c)
        {:keys [props plain-joins recur-joins all-children]} (eql-query-keys-by-type query)]
    (run! (fn [p] (reg-sub-prop id-attr p)) props)
    (run! (fn [[p c]] (reg-sub-plain-join id-attr p c)) plain-joins)
    (run! (fn [[p]] (reg-sub-recur-join id-attr p entity-sub)) recur-joins)
    (reg-sub-entity id-attr entity-sub all-children)
    nil))

;; components like this are the input (created by a dev etc):
;; these are your domain entities

(def comment-comp (rc/nc [:comment/id :comment/text] {:componentName ::comment :ident (fn [_ p] [:comment/id (:comment/id p)])}))
(def comment-q (rc/get-query comment-comp))
(rc/class->registry-key comment-comp)
(rc/component-name comment-comp)

(def comment-recur-comp (rc/nc [:comment/id :comment/text {:comment/sub-comments '...}] {:componentName ::comment :ident (fn [_ p] [:comment/id (:comment/id p)])}))
(def comment-r-q (rc/get-query comment-recur-comp))

(def todo-comp (rc/nc [:todo/id :todo/text {:todo/comment (rc/get-query comment-comp)}] {:componentName ::todo :ident (fn [_ p] [:todo/id (:todo/id p)])}))
(def todo-q (rc/get-query todo-comp))

(rc/get-ident comment-comp {:comment/id 5})
(rc/get-ident comment-comp {:comment/id 5 :comment/hi "hi"})
(rc/get-ident todo-comp {:todo/id 5 :todo/comment {:comment/text "hi"}})

(:join (group-by :type (:children (eql/query->ast todo-q))))
(eql-query-keys-by-type comment-q)
(eql-query-keys-by-type comment-r-q)
(eql-query-keys-by-type todo-q)

;; now we can register the handlers
;(map (fn [p] (reg-sub-prop :todo/id p)) (:props (eql-query-keys-by-type todo-q)))
;(map (fn [[p c]] (reg-sub-plain-join :todo/id p c)) (:plain-joins (eql-query-keys-by-type todo-q)))
;(map (fn [[p c]] (reg-sub-recur-join :todo/id p c)) (:recur-joins (eql-query-keys-by-type todo-q)))
;(reg-sub-entity :todo/id ::todo (:all-children (eql-query-keys-by-type todo-q)))
(component->reg-subs comment-recur-comp)
(component->reg-subs todo-comp)

;; and then reg-sub for the component itself ::todo - that one is layer 3
(comment
  (<sub db_ [:comment/id {:comment/id 1}])
  (<sub db_ [:comment/text {:comment/id 1}])
  (<sub db_ [:comment/sub-comments {:comment/id 1 ::subs/max-recur-depth 1}])

  (<sub db_ [:todo/text {:todo/id 1}])
  (<sub db_ [:todo/comment {:todo/id 1}])
  (<sub db_ [::todo {:todo/id 1}])
  (<sub db_ [:comment/id {:comment/id 1}])
  ;; now i'm seeing it - this is how you can get the control of what to lookup as the tree of subs is realized:
  (<sub db_ [::todo {:todo/id     1
                     ::subs/query [:todo/id
                                   {:todo/comment [:comment/id :comment/text
                                                   {:comment/subs 2}]}]}])

  ;;; but you could just do:

  (let [args {:todo/id 1}]
    {:todo/id   (<sub db_ [:todo/id args])
     :todo/text (<sub db_ [:todo/text args])})

  ;; you pass an eql query as the ::subs/query key and the implementation of the fns does select-keys type of thing
  ;; pulling out the nested params as you traverse
  ;; the cool thing is that you can support both - the first version are the implementation primitives of the second version

  ;; todo:
  ;; to-one and to-many joins for plain and recur
  ;; union query support
  ;; sub-select the part of the query by passing them as args
  )
;(<sub db_ [::comment {:comment/id 1}])


(map (fn [[prop component-sub]] (reg-sub-plain-join :todo/id prop component-sub)) (:plain-joins (eql-query-keys-by-type todo-q)))
;(<sub db_ [:todo/comment {:todo/id 1}])

(def union-q
  [{:chat/entries
    {:message/id [:message/id :message/text :chat.entry/timestamp]
     :audio/id   [:audio/id :audio/url :audio/duration :chat.entry/timestamp]
     :photo/id   [:photo/id :photo/url :photo/width :photo/height :chat.entry/timestamp]}}])

(eql/query->ast union-q)
(group-by :type (:children (eql/query->ast union-q)))
;(eql-query-keys-by-type union-q)

(comment
  (eql/query->ast1 [:todo/id :todo/text :todo/state :todo/completed-at])
  (eql/query->ast [:todo/id :todo/text :todo/state :todo/completed-at
                   {:todo/comment
                    {:comment/video [:video/id :video/url :video/play-length]
                     :comment/text  [:text-comment/id :text-comment/text]
                     :comment/audio [:audio/id :audio/url]
                     }
                    }])

  (eql-query-keys-by-type
    [:todo/id :todo/text :todo/state :todo/completed-at]
    )
  )

;; this is intended to be used on the lowest level of your db - just for normalized data

;; for example for any nesting, like in this union:
[{:chat/entries
  {:message/id [:message/id :message/text :chat.entry/timestamp]
   :audio/id   [:audio/id :audio/url :audio/duration :chat.entry/timestamp]
   :photo/id   [:photo/id :photo/url :photo/width :photo/height :chat.entry/timestamp]}}]
;the assumption is that message, audio, photo have been registered already themselves
