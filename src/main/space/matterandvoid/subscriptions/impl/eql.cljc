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

;; steps:
; write reg-sub for each block property - figure out hte join

(reg-sub :block-table (fn [db]
                        (println "get block table")
                        (get db :block/id)))
(reg-sub :normalized-block
  :<- [:block-table]
  (fn [table [_ {:block/keys [id]}]]
    (def t' table)
    (println "normalized block lookup: " id)
    (get table id ::missing)))

;(reg-sub :block/id-orig
;  (fn [[_ args]]
;    (println "block id ninputs fn args" args)
;    (subscribe [:normalized-block args]))
;  (fn [block [_ {:block/keys [id]}]]
;    (println "compute sub :block/id")
;    (get block :block/id ::missing)))

(reg-sub :block/id
  (fn [db [_ {:block/keys [id]}]]
    ;(println "compute sub :block/id")
    (get-in db [:block/id id :block/id] ::missing)))

(reg-sub :block/type
  (fn [db [_ {:block/keys [id]}]]
    ;(println "compute sub :block/type")
    (get-in db [:block/id id :block/type] ::missing)))

(reg-sub :block/properties
  (fn [db [_ {:block/keys [id]}]]
    ;(println "compute sub :block/props")
    (get-in db [:block/id id :block/properties] ::missing)))

;(reg-sub :block/type-orig (fn [[_ args]] (subscribe [:normalized-block args])) (fn [block] (get block :block/type ::missing)))
;(reg-sub :block/properties-orig (fn [[_ args]] (subscribe [:normalized-block args]))
;  (fn [block] (get block :block/properties ::missing)))

;; This is the recursion

(reg-sub-raw :block/children
  (fn [db_ [_ {:recur/keys [depth max-depth] :as args}]]
    (make-reaction
      (fn []
        (println "sub-raw children: " args)
        (let [refs (filter some? (get-in @db_ [:block/id (:block/id args) :block/children]))]
          (println "---------------Refs: " refs)
          (cond
            (and refs (> max-depth depth))
            (map (fn [[_ id]] @(subscribe [::block {:recur/depth (inc depth) :recur/max-depth max-depth :block/id id}]))
              refs)
            ;; do not recur
            refs refs
            :else nil))))))

;; you can't do this:
(reg-sub :block/children (fn [[_ args]] (subscribe [:block/children-refs args]))
  (fn [refs] (map (fn [[_ id]] @(subscribe [::block {:block/id id}])) refs)))
;; because the compute function is memoized and thus will be stale


(reg-sub ::block
  (fn [[_ args]]
    (println "IN ::block inputs" (:block/id args))
    {:block/id         (subscribe [:block/id args])
     :block/type       (subscribe [:block/type args])
     :block/properties (subscribe [:block/properties args])
     :block/children   (subscribe [:block/children args])})
  (fn [block]
    (cond-> block (empty? (:block/children block)) (dissoc :block/children))))

(def db_ (r/atom {:comment/id {1 {:comment/id 1 :comment/text "FIRST COMMENT"}}
                  :todo/id    {1 {:todo/id      1 :todo/text "hi"
                                  :todo/comment [:comment/id 1]}}}))

;(reg-event-db :put-db (fn [db] (merge db my-db2)))
;(reg-event-db :change-it
;  (fn [db]
;    (update db :dan-num inc)))

(subs/clear-subscription-cache! db_)
(comment
  (update-in {} [:dan-num] inc)
  (rf/dispatch [:change-it])
  (rf/dispatch [:put-db])

  @(subscribe [:block-table])
  @(subscribe [:normalized-block {:block/id #uuid"900e63a5-0ad2-4710-aee2-84d6aeae1339"}])
  @(subscribe [:block/id {:block/id #uuid"900e63a5-0ad2-4710-aee2-84d6aeae1339"}])
  @(subscribe [:block/type {:block/id #uuid"900e63a5-0ad2-4710-aee2-84d6aeae1339"}])
  @(subscribe [:block/properties {:block/id #uuid"900e63a5-0ad2-4710-aee2-84d6aeae1339"}])
  @(subscribe [:block/children-refs {:block/id #uuid"900e63a5-0ad2-4710-aee2-84d6aeae1339"}])
  @(subscribe [:block/children {:block/id #uuid"900e63a5-0ad2-4710-aee2-84d6aeae1339" :recur/depth 0 :recur/max-depth 1}])
  @(subscribe [::block {:block/id #uuid"900e63a5-0ad2-4710-aee2-84d6aeae1339"}])

  ;;   and also do:
  ;; you would have to extend this per-join though.
  @(subscribe [::block {:block/id       #uuid"900e63a5-0ad2-4710-aee2-84d6aeae1339"
                        ;; something like this:
                        :subs/recursion {:block/children {:recur/max-depth 1 :recur/depth 0}}
                        }])
  ;; :
  @(subscribe [::block {:block/id #uuid"900e63a5-0ad2-4710-aee2-84d6aeae1339" :recur/max-depth 1 :recur/depth 0}]))


;; Problem statement:
;; I have a fulcro query and am using subscriptions, I want to invoke (subscribe) with the keys of that query without
;; having to write reg-subs manually.

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
(defn error [& args]
  (Exception. ^String (apply str args)))

;; todo I think I need to add a little query logic for splitting the joins into recursive and non-recursive
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
      (if-let [entity-id (get args id-attr)]
        (let [entity (get-in db [id-attr entity-id])]
          (get entity prop))
        (throw (error "Missing id attr: " id-attr " in args map passed to subscription: " prop))))))


(defn reg-sub-entity
  [id-kw entity-kw props]
  (reg-sub entity-kw
    (fn [db_ args]
      (reduce (fn [acc prop] (assoc acc prop (subscribe db_ [prop args])))
        {} props))
    identity))

(defn reg-sub-entity
  "Registers a subscription that returns a domain entity as a hashmap.
  id-attr for the entity, the entity subscription name (fq kw) a seq of dependent subscription props"
  [id-kw entity-kw props]
  (reg-sub-raw entity-kw
    (fn [db_ args]
      (when-not (get args id-kw) (throw (error "subscription " (pr-str entity-kw) " missing id argument: " args)))
      (reduce (fn [acc prop] (assoc acc prop (subscribe db_ [prop args]))) {} props))))

(defn reg-sub-plain-join
  "Takes two keywords: id attribute and property attribute, registers a layer 2 subscription using the id to lookup the
  entity and extract the property."
  [id-attr join-prop join-component]

  ;; get the joined entity - it will be a ref
  ;; reg-sub-raw
  ;; lookup the entity


  (reg-sub-raw join-prop
    (fn [db_ args]
      (make-reaction
        (fn []
          (if-let [entity-id (get args id-attr)]
            (let [entity (get-in db_ [id-attr entity-id])
                  [join-kw join-id] (get entity join-prop)]
              ;; now we need a convention for :comment/id -> subscription for that comment
              ;; it is the component name!
              ;;
              (subscribe [join-kw] (get entity join-prop)))
            (throw (error "Missing id attr: " id-attr " in args map passed to subscription: " join-prop))))))))

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
(map (fn [p] (reg-sub-prop :todo/id p)) (:props (eql-query-keys-by-type todo-q)))
(reg-sub-entity :todo/id ::todo (:all-children (eql-query-keys-by-type todo-q)))
;; and then reg-sub for the component itself ::todo - that one is layer 3
(<sub db_ [:todo/text {:todo/id 1}])
(<sub db_ [::todo {:todo/id 1}])

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
