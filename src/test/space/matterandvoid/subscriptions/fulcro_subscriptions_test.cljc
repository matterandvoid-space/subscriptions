(ns space.matterandvoid.subscriptions.fulcro-subscriptions-test
  (:require
    [space.matterandvoid.subscriptions.impl.fulcro-subscriptions :as sut]
    [space.matterandvoid.subscriptions.impl.reagent-ratom :as r]
    [space.matterandvoid.subscriptions.fulcro :as subs :refer [reg-sub reg-sub-raw subscribe <sub]]
    [com.fulcrologic.fulcro.application :as fulcro.app]
    [com.fulcrologic.fulcro.raw.components :as rc]
    [clojure.test :refer [deftest is testing]]))

(def comment-comp (sut/nc {:query [:comment/id :comment/text] :name ::comment :ident :comment/id}))
(def comment-q (rc/get-query comment-comp))
(def comment-recur-comp (sut/nc {:query [:comment/id :comment/text {:comment/sub-comments '...}] :name ::comment :ident :comment/id}))
(def comment-r-q (rc/get-query comment-recur-comp))
(def todo-comp (sut/nc {:query [:todo/id :todo/text {:todo/comment (rc/get-query comment-comp)}] :name ::todo :ident :todo/id}))
(def todo-q (rc/get-query todo-comp))
(def list-member-comp (sut/nc {:query {:comment/id (rc/get-query comment-recur-comp) :todo/id todo-q}
                               :name  ::list-member}))
(def list-comp (sut/nc {:ident :list/id
                        :name  ::list
                        :query [:list/id :list/name
                                {:list/members (rc/get-query list-member-comp)}]}))
(def list-q (rc/get-query list-comp))


(def db_ (r/atom {:comment/id {1 {:comment/id           1 :comment/text "FIRST COMMENT"
                                  :comment/sub-comments [[:comment/id 2]]}
                               2 {:comment/id 2 :comment/text "SECOND COMMENT"}
                               3 {:comment/id 3 :comment/text "THIRD COMMENT"}}
                  :list/id    {1 {:list/id 1 :list/name "first list" :list/members [[:comment/id 1] [:todo/id 2]]}}
                  :todo/id    {1 {:todo/id      1 :todo/text "todo 1"
                                  :todo/comment [:comment/id 1]}
                               2 {:todo/id 2 :todo/text "todo 2"}}}))

(defonce app (assoc (fulcro.app/fulcro-app {}) ::fulcro.app/state-atom db_))

(sut/component-reg-subs! comment-recur-comp)
(sut/component-reg-subs! todo-comp)
(sut/component-reg-subs! list-comp)

(deftest queries-test
  (let [out (<sub db_ [::list {:list/id 1 ::subs/query [#_:list/name {:list/members {:comment/id [:comment/id :comment/text]
                                                                                     :todo/id    [:todo/id :todo/text]}}]}])])

  )
(comment
  (subs/clear-handlers app)
  (<sub app [::list {:list/id 1}])
  (<sub app [::list {:list/id 1 ::subs/query [#_:list/name {:list/members {:comment/id [:comment/id :comment/text]
                                                                           :todo/id    [:todo/id :todo/text]}}]}]))


