(ns space.matterandvoid.subscriptions.fulcro-queries-test
  (:require
    ;[space.matterandvoid.subscriptions.impl.fulcro-queries-debug :as sut]
    [space.matterandvoid.subscriptions.fulcro-queries :as sut]
    [space.matterandvoid.subscriptions.impl.reagent-ratom :as r]
    [space.matterandvoid.subscriptions.fulcro :as subs :refer [reg-sub reg-sub-raw subscribe <sub]]
    [com.fulcrologic.fulcro.application :as fulcro.app]
    [edn-query-language.core :as eql]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.raw.components :as rc]
    [taoensso.timbre :as log]
    [clojure.test :refer [deftest is testing]]))
;(ns-unalias *ns* 'sut)

;; idea to use a predicate to determine recursion - this is not part of eql currently
;(eql/query->ast [:comment/id :comment/text {:comment/sub-comments `traverse?}])

(log/set-level! :debug)
(enable-console-print!)
(set! *print-namespace-maps* false)

(def user-comp (sut/nc {:query [:user/id :user/name {:user/friends '...}] :name ::user :ident :user/id}))
(def bot-comp (sut/nc {:query [:bot/id :bot/name] :name ::bot :ident :bot/id}))
(def human-comp (sut/nc {:query [:human/id :human/name {:human/best-friend 1}] :name ::human :ident :human/id}))
(def author-comp (sut/nc {:query {:bot/id  (rc/get-query bot-comp)
                                  :user/id (rc/get-query user-comp)}
                          :name  ::author}))
(def comment-comp (sut/nc {:query [:comment/id :comment/text {:comment/sub-comments '...}] :name ::comment :ident :comment/id}))
(def todo-comp (sut/nc {:query [:todo/id :todo/text {:todo/comment (rc/get-query comment-comp)}
                                {:todo/comments (rc/get-query comment-comp)}
                                {:todo/author (rc/get-query author-comp)}] :name ::todo :ident :todo/id}))
(def todo-q (rc/get-query todo-comp))
(def list-member-comp (sut/nc {:query {:comment/id (rc/get-query comment-comp) :todo/id todo-q} :name ::list-member}))
(def list-member-q (rc/get-query list-member-comp))
(def list-comp (sut/nc {:ident :list/id :name ::list
                        :query [:list/id :list/name
                                {:list/items (rc/get-query list-member-comp)}
                                {:list/members (rc/get-query list-member-comp)}]}))

(run! sut/reg-component-subs! [user-comp bot-comp comment-comp todo-comp list-comp human-comp])

(def db_ (r/atom {:comment/id {1 {:comment/id           1 :comment/text "FIRST COMMENT"
                                  :comment/sub-comments [[:comment/id 2]]}
                               2 {:comment/id 2 :comment/text "SECOND COMMENT"}
                               3 {:comment/id 3 :comment/text "THIRD COMMENT"}}
                  :list/id    {1 {:list/id      1 :list/name "first list"
                                  :list/members [[:comment/id 1] [:todo/id 2]]
                                  :list/items   [[:todo/id 2] [:comment/id 1]]}}
                  ;; to-one cycles
                  :human/id   {1 {:human/id 1 :human/name "human Y" :human/best-friend [:human/id 1]}
                               2 {:human/id 2 :human/name "human X" :human/best-friend [:human/id 3]}
                               3 {:human/id 3 :human/name "human Z" :human/best-friend [:human/id 1]}}

                  ;; to-many cycle
                  :user/id    {1 {:user/id 1 :user/name "user 1" :user/friends [[:user/id 2]]}
                               2 {:user/id 2 :user/name "user 2" :user/friends [[:user/id 2] [:user/id 1] [:user/id 3]]}
                               3 {:user/id 3 :user/name "user 3" :user/friends [[:user/id 2] [:user/id 4]]}
                               4 {:user/id 4 :user/name "user 4" :user/friends [[:user/id 3] [:user/id 4]]}}
                  :bot/id     {1 {:bot/id 1 :bot/name "bot 1"}}
                  :todo/id    {1 {:todo/id      1 :todo/text "todo 1"
                                  :todo/author  [:bot/id 1]
                                  :todo/comment [:comment/id 1]}
                               2 {:todo/id 2 :todo/text "todo 2" :todo/author [:user/id 2]}
                               3 {:todo/id 3 :todo/text "todo 3" :todo/comments [[:comment/id 1] [:comment/id 3]]}}}))

(def app (assoc (fulcro.app/fulcro-app {}) ::fulcro.app/state-atom db_))

(comment
  (<sub app [::user {:user/id 1 sut/query-key [:user/name]}])
  (<sub app [::user {:user/id 1 sut/query-key [:user/name :user/id {:user/friends 1}]}])
  (<sub app [::user {:user/id 1 sut/query-key [:user/name :user/id {:user/friends 0}]}])
  (<sub app [::user {:user/id 1 sut/query-key [:user/name :user/id {:user/friends '...}]}])
  )

;(<sub app [::user {:user/id 1 sut/query-key [:user/id {:user/friends `keep-walking?}]}])

;; prop queries
;; - individual kw
;; - '* attribute

;; plain join
;;   - to-one
;;   - to-many

;; recur join
;;   - to-one
;;   - to-many

;; handle cycles https://book.fulcrologic.com/#_circular_recursion
;; see ns com.fulcrologic.fulcro.algorithms.denormalize

;; union join
;;   - to-one
;;   - to-many

(deftest union-queries-test
  (testing "to-one union queries"
    (is (= {:todo/id 1, :todo/author {:bot/name "bot 1", :bot/id 1}}
          (<sub app [::todo {:todo/id 1 sut/query-key [:todo/id :todo/author]}])))
    (is (=
          {:todo/id 2, :todo/author #:user{:id 2, :name "user 2", :friends [[:user/id 2] [:user/id 1] [:user/id 3]]}}
          (<sub app [::todo {:todo/id 2 sut/query-key [:todo/id :todo/author]}])))
    (is (= {:todo/id 2, :todo/author {:user/name "user 2"}}
          (<sub app [::todo {:todo/id 2 sut/query-key [:todo/id {:todo/author {:user/id        [:user/name]
                                                                              :does-not/exist [:a :b :c]}}]}])))
    (testing "support for * query"
      (is (= #:todo{:id 1, :author #:bot{:id 1, :name "bot 1"}} (<sub app [::todo {:todo/id 1 sut/query-key [:todo/id {:todo/author ['*]}]}])))
      (is (= #:todo{:id 2, :author #:user{:id 2, :name "user 2", :friends [[:user/id 2] [:user/id 1] [:user/id 3]]}}
            (<sub app [::todo {:todo/id 2 sut/query-key [:todo/id {:todo/author '[*]}]}])))))

  (testing "to-many union queries"
    (is (=
          #:list{:items   [#:todo{:id     2, :text "todo 2",
                                  :author #:user{:id 2, :name "user 2", :friends [#:user{:id 2, :name "user 2", :friends sut/cycle-marker}
                                                                                  #:user{:id 1, :name "user 1", :friends [#:user{:id 2, :name "user 2", :friends sut/cycle-marker}]}
                                                                                  #:user{:id      3, :name "user 3",
                                                                                         :friends [#:user{:id 2, :name "user 2", :friends sut/cycle-marker}
                                                                                                   #:user{:id      4, :name "user 4",
                                                                                                          :friends [#:user{:id 3, :name "user 3", :friends sut/cycle-marker}
                                                                                                                    #:user{:id 4, :name "user 4", :friends sut/cycle-marker}]}]}]}}
                           #:comment{:id 1, :text "FIRST COMMENT", :sub-comments [#:comment{:id 2, :text "SECOND COMMENT"}]}],
                 :members [#:comment{:id 1, :text "FIRST COMMENT"} #:todo{:id 2, :text "todo 2"}]}

          (<sub app [::list {:list/id 1 sut/query-key [{:list/items list-member-q}
                                                      {:list/members {:comment/id [:comment/id :comment/text] :todo/id [:todo/id :todo/text]}}]}])))

    (testing "unions should only return queried-for branches"
      (is (= {:list/items []} (<sub app [::list {:list/id 1 sut/query-key [{:list/items {:todo2/id [:todo/id :todo/text]}}]}])))
      (is (= {:list/items [{:todo/id 2, :todo/text "todo 2"}]}
            (<sub app [::list {:list/id 1 sut/query-key [{:list/items {:todo/id [:todo/id :todo/text]}}]}]))))))

(deftest plain-join-queries

  (testing "to-one joins"
    (is (= #:todo{:id 1, :author #:bot{:id 1, :name "bot 1"}, :comment #:comment{:id 1, :text "FIRST COMMENT", :sub-comments [[:comment/id 2]]}}
          (<sub app [::todo {:todo/id 1 sut/query-key [:todo/id :todo/author :todo/comment]}])))
    (is (= #:todo{:id 1,, :comment #:comment{:id 1, :text "FIRST COMMENT"}}
          (<sub app [::todo {:todo/id 1 sut/query-key [:todo/id {:todo/comment [:comment/id :comment/text]}]}]))))

  (testing "to-many joins"
    (is (= {:todo/id 2} (<sub app [::todo {:todo/id 2 sut/query-key [:todo/id {:todo/comments [:comment/id :comment/text]}]}])))
    (is (= #:todo{:id 3, :comments [#:comment{:id 1, :text "FIRST COMMENT"} #:comment{:id 3, :text "THIRD COMMENT"}]}
          (<sub app [::todo {:todo/id 3 sut/query-key [:todo/id {:todo/comments [:comment/id :comment/text]}]}])))
    (testing "support for '[*]"
      (is (=
            {:todo/id       3,
             :todo/comments [{:comment/sub-comments [[:comment/id 2]], :comment/id 1, :comment/text "FIRST COMMENT"}
                             {:comment/sub-comments sut/missing-val,
                              :comment/id           3,
                              :comment/text         "THIRD COMMENT"}]}
            (<sub app [::todo {:todo/id 3 sut/query-key [:todo/id {:todo/comments ['*]}]}]))))))

(deftest recursive-join-queries
  (is (= #:user{:name "user 1", :id 1, :friends [[:user/id 2]]}
        (<sub app [::user {:user/id 1 sut/query-key [:user/name :user/id {:user/friends 0}]}])))

  (is (= #:user{:name    "user 1", :id 1,
                :friends [#:user{:name "user 2", :id 2, :friends [[:user/id 2] [:user/id 1] [:user/id 3]]}]}
        (<sub app [::user {:user/id 1 sut/query-key [:user/name :user/id {:user/friends 1}]}])))

  (testing "handles self-cycle"
    (is (=
          {:human/id 1, :human/best-friend [:human/id 1], :human/name "human Y"}
          (<sub app [::human {:human/id 1 sut/query-key [:human/id :human/best-friend :human/name]}])))

    (is (= {:human/id 1, :human/best-friend sut/cycle-marker, :human/name "human Y"}
          (<sub app [::human {:human/id 1 sut/query-key [:human/id {:human/best-friend '...} :human/name]}])))

    (testing "handles multi-level to-one cycle"
      (is (=
            {:human/id          2,
             :human/best-friend {:human/id          3,
                                 :human/best-friend {:human/id          1,
                                                     :human/best-friend :space.matterandvoid.subscriptions.fulcro/cycle,
                                                     :human/name        "human Y"},
                                 :human/name        "human Z"},
             :human/name        "human X"}))
      (<sub app [::human {:human/id 2 sut/query-key [:human/id {:human/best-friend '...} :human/name]}]))

    (testing "handles finite self-recursive (to-one) cycles"
      (is (= {:human/id          1,
              :human/best-friend {:human/id          1,
                                  :human/best-friend {:human/id 1, :human/best-friend sut/cycle-marker, :human/name "human Y"},
                                  :human/name        "human Y"},
              :human/name        "human Y"}
            (<sub app [::human {:human/id 1 sut/query-key [:human/id {:human/best-friend 2} :human/name]}])))))

  (testing "handles to-many recursive cycles"
    (is (=
          #:user{:name    "user 1", :id 1,
                 :friends [#:user{:name    "user 2", :id 2,
                                  :friends [#:user{:name "user 2", :id 2, :friends sut/cycle-marker}
                                            #:user{:name "user 1", :id 1, :friends sut/cycle-marker}
                                            #:user{:name    "user 3", :id 3,
                                                   :friends [#:user{:name "user 2", :id 2, :friends sut/cycle-marker}
                                                             #:user{:name    "user 4", :id 4,
                                                                    :friends [#:user{:name "user 3", :id 3, :friends sut/cycle-marker}
                                                                              #:user{:name "user 4", :id 4, :friends sut/cycle-marker}]}]}]}]}
          (<sub app [::user {:user/id 1 sut/query-key [:user/name :user/id {:user/friends '...}]}])))))

(deftest queries-test
  (testing "props"
    (is (= {:todo/id 1 :todo/text "todo 1"} (<sub app [::todo {:todo/id 1 sut/query-key [:todo/id :todo/text]}])))
    (is (=
          #:todo{:comment  [:comment/id 1],
                 :comments sut/missing-val
                 :author   [:bot/id 1],
                 :id       1,
                 :text     "todo 1"}
          (<sub app [::todo {:todo/id 1 sut/query-key ['* :todo/text]}]))))

  (testing "entity subscription with no query returns all attributes"
    (is (= {:list/members [[:comment/id 1] [:todo/id 2]]
            :list/items   [[:todo/id 2] [:comment/id 1]], :list/name "first list", :list/id 1}
          (<sub app [::list {:list/id 1}])))))

(comment
  (<sub app [::list {:list/id 1 sut/query-key [{:list/items list-member-q}
                                              {:list/members {:comment/id [:comment/id :comment/text] :todo/id [:todo/id :todo/text]}}]}])
  (<sub app [::todo {:todo/id 1 sut/query-key [:todo/id :todo/author]}])
  (<sub app [::list {:list/id 1}])
  (<sub app [::list {:list/id 1 sut/query-key [#_:list/name {:list/members {:comment/id [:comment/id :comment/text]
                                                                           :todo/id    [:todo/id :todo/text]}}]}])
  (<sub app [::list {:list/id 1 sut/query-key [:list/name {:list/members {:comment/id [:comment/id :comment/text
                                                                                      {:comment/sub-comments '...}]
                                                                         :todo/id    [:todo/id :todo/text]}}]}])
  (<sub app [::list {:list/id 1 sut/query-key [:list/name
                                              {:list/members {:comment/id [:comment/id :comment/text {:comment/sub-comments '...}] :todo/id [:todo/id :todo/text]}}
                                              {:list/items

                                               {:comment/id [:comment/id :comment/text {:comment/sub-comments 0}] :todo/id [:todo/id :todo/text]}
                                               }
                                              ]}])
  (<sub app [::list {:list/id 1 sut/query-key [; :list/name
                                              ;{:list/members (rc/get-query list-member-comp)}
                                              {:list/items {
                                                            ;:comment/id [:comment/id :comment/text {:comment/sub-comments 0}]
                                                            :todo/id [:todo/id :todo/text]}}
                                              ]}])

  )
