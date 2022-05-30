(ns space.matterandvoid.subscriptions.fulcro-subscriptions-test
  (:require
    [space.matterandvoid.subscriptions.impl.fulcro-subscriptions :as sut]
    [space.matterandvoid.subscriptions.impl.reagent-ratom :as r]
    [space.matterandvoid.subscriptions.fulcro :as subs :refer [reg-sub reg-sub-raw subscribe <sub]]
    [com.fulcrologic.fulcro.application :as fulcro.app]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.raw.components :as rc]
    [clojure.test :refer [deftest is testing]]))
(set! *print-namespace-maps* false)

(def user-comp (sut/nc {:query [:user/id :user/name] :name ::user :ident :user/id}))
(def bot-comp (sut/nc {:query [:bot/id :bot/name] :name ::bot :ident :bot/id}))
(def author-comp (sut/nc {:query {:bot/id  (rc/get-query bot-comp)
                                  :user/id (rc/get-query user-comp)}
                          :name  ::author}))
(def comment-comp (sut/nc {:query [:comment/id :comment/text {:comment/sub-comments '...}] :name ::comment :ident :comment/id}))
(def todo-comp (sut/nc {:query [:todo/id :todo/text {:todo/comment (rc/get-query comment-comp)}
                                {:todo/author (rc/get-query author-comp)}] :name ::todo :ident :todo/id}))
(def todo-q (rc/get-query todo-comp))
(def list-member-comp (sut/nc {:query {:comment/id (rc/get-query comment-comp) :todo/id todo-q} :name ::list-member}))
(def list-comp (sut/nc {:ident :list/id :name ::list
                        :query [:list/id :list/name
                                {:list/items (rc/get-query list-member-comp)}
                                {:list/members (rc/get-query list-member-comp)}]}))

(def db_ (r/atom {:comment/id {1 {:comment/id           1 :comment/text "FIRST COMMENT"
                                  :comment/sub-comments [[:comment/id 2]]}
                               2 {:comment/id 2 :comment/text "SECOND COMMENT"}
                               3 {:comment/id 3 :comment/text "THIRD COMMENT"}}
                  :list/id    {1 {:list/id 1 :list/name "first list" :list/members [[:comment/id 1] [:todo/id 2]]
                                  :list/items [[:comment/id 1] [:todo/id 2]]}}
                  :user/id    {1 {:user/id 1 :user/name "user 1"}
                               2 {:user/id 2 :user/name "user 2"}
                               3 {:user/id 3 :user/name "user 3"}}
                  :bot/id     {1 {:bot/id 1 :bot/name "bot 1"}}
                  :todo/id    {1 {:todo/id      1 :todo/text "todo 1"
                                  :todo/author  [:bot/id 1]
                                  :todo/comment [:comment/id 1]}
                               2 {:todo/id     2 :todo/text "todo 2"
                                  :todo/author [:user/id 2]}}}))


(sut/component-reg-subs! user-comp)
(sut/component-reg-subs! bot-comp)
(sut/component-reg-subs! comment-comp)
(sut/component-reg-subs! todo-comp)
(sut/component-reg-subs! list-comp)

(deftest queries-test
  (let [app (assoc (fulcro.app/fulcro-app {}) ::fulcro.app/state-atom db_)
        out (<sub app [::list {:list/id 1 ::subs/query [#_:list/name {:list/members {:comment/id [:comment/id :comment/text]
                                                                                     :todo/id    [:todo/id :todo/text]}}]}])
        ]

    (testing "entity subscription with no query returns all attributes"
      (is (= {:list/members [[:comment/id 1] [:todo/id 2]], :list/name "first list", :list/id 1}
            (<sub app [::list {:list/id 1}]))))

    (testing "to-one union queries"
      (is (= {:todo/id 1, :todo/author {:bot/name "bot 1", :bot/id 1}}
            (<sub app [::todo {:todo/id 1 ::subs/query [:todo/id :todo/author]}])))
      (is (= {:todo/id 2, :todo/author {:user/name "user 2", :user/id 2}}
            (<sub app [::todo {:todo/id 2 ::subs/query [:todo/id :todo/author]}])))
      )
    (testing "to-many union queries"
      (is (=
            (<sub app [::list {:list/id 1 ::subs/query [{:list/members {:comment/id [:comment/id :comment/text] :todo/id [:todo/id :todo/text]}}]}])
            {:list/members [{:comment/id 1, :comment/text "FIRST COMMENT"} {:todo/id 2, :todo/text "todo 2"}]})))))

(def app2 (assoc (fulcro.app/fulcro-app {}) ::fulcro.app/state-atom db_))
(comment
  (subs/clear-handlers app2)
  (<sub app2 [::todo {:todo/id 1 ::subs/query [:todo/id :todo/author]}])
  (<sub app2 [::list {:list/id 1}])
  (<sub app2 [::list {:list/id 1}])
  (<sub app2 [::list {:list/id 1 ::subs/query [#_:list/name {:list/members {:comment/id [:comment/id :comment/text]
                                                                           :todo/id    [:todo/id :todo/text]}}]}])
  (<sub app2 [::list {:list/id 1 ::subs/query [:list/name {:list/members {:comment/id [:comment/id :comment/text
                                                                                      {:comment/sub-comments '...}]
                                                                         :todo/id    [:todo/id :todo/text]}}]}])
  (<sub app2 [::list {:list/id 1 ::subs/query [:list/name
                                               {:list/members {:comment/id [:comment/id :comment/text {:comment/sub-comments '...}] :todo/id    [:todo/id :todo/text]}}
                                               {:list/items {:comment/id [:comment/id :comment/text {:comment/sub-comments 0}] :todo/id    [:todo/id :todo/text]}}
                                               ]}])

  )


