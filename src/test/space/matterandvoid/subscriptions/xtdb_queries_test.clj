(ns space.matterandvoid.subscriptions.xtdb-queries-test
  (:require
    [space.matterandvoid.subscriptions.xtdb-queries :as sut]
    [space.matterandvoid.subscriptions.core :as subs :refer [<sub]]
    [space.matterandvoid.subscriptions :as-alias subs-keys]
    [space.matterandvoid.subscriptions.impl.reagent-ratom :as r]
    [com.fulcrologic.fulcro.raw.components :as rc]
    [taoensso.timbre :as log]
    [edn-query-language.core :as eql]
    [xtdb.api :as xt]
    [clojure.test :refer [deftest is testing]]))


;; idea to use a predicate to determine recursion - this is not part of eql currently
;(eql/query->ast [:comment/id :comment/text {:comment/sub-comments `traverse?}])

(defonce xt-node (xt/start-node {}))

(log/set-level! :debug)
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

;; for now to get this working I am having all refs be idents or vec of idents
;; later you can make this generic
;; i'm thinking the protocol would have -attr for simple getter
;; and -join-attr - which must return one of: nil, ident, vec of idents
;; join-attr will be used for any join operations
;; this way you have some flexibility in how normalized data is stored in whatever storage you're using.

(xt/submit-tx xt-node
  [[::xt/put {:xt/id :comment-1 :comment/id :comment-1 :comment/text "FIRST COMMENT" :comment/sub-comments [:comment/id :comment-2]}]
   [::xt/put {:xt/id :comment-2 :comment/id :comment-2 :comment/text "SECOND COMMENT"}]
   [::xt/put {:xt/id :comment-3 :comment/id :comment-3 :comment/text "THIRD COMMENT"}]
   [::xt/put {:xt/id        :list-1 :list/id :list-1 :list/name "first list"
              :list/members [[:comment/id :comment-1] [:todo/id :todo-2]]
              :list/items   [[:todo/id :todo-2] [:comment/id :comment-1]]}]

   ;; to-one cycle
   [::xt/put {:xt/id :human-1 :human/id :human-1 :human/name "human Y" :human/best-friend [:human/id :humnan-1]}]
   [::xt/put {:xt/id :human-2 :human/id :human-2 :human/name "human X" :human/best-friend [:human/id :human-3]}]
   [::xt/put {:xt/id :human-3 :human/id :human-3 :human/name "human Z" :human/best-friend [:human/id :human-1]}]

   ;; to-many cycle
   [::xt/put {:xt/id :user-1 :user/id :user-1 :user/name "user 1" :user/friends [[:user/id :user-2]]}]
   [::xt/put {:xt/id :user-2 :user/id :user-2 :user/name "user 2" :user/friends [[:user/id :user-2] [:user/id :user-1] [:user/id :user-3]]}]
   [::xt/put {:xt/id :user-3 :user/id :user-3 :user/name "user 3" :user/friends [[:user/id :user-2] [:user/id :user-4]]}]
   [::xt/put {:xt/id :user-4 :user/id :user-4 :user/name "user 4" :user/friends [[:user/id :user-3] [:user/id :user-4]]}]

   [::xt/put {:xt/id :bot-1 :bot/id :bot-1 :bot/name "bot 1"}]

   ;; union queries
   [::xt/put {:xt/id :tood-1 :todo/id :todo-1 :todo/text "todo 1" :todo/author [:bot/id :bot-1] :todo/comment [:comment/id :comment-1]}]
   [::xt/put {:xt/id :tood-2 :todo/id :todo-2 :todo/text "todo 2" :todo/author [:user/id :user-2]}]
   [::xt/put {:xt/id :tood-3 :todo/id :todo-3 :todo/text "todo 3" :todo/comments [[:comment/id :comment-1] [:comment/id :comment-3]]}]


   [::xt/put {:xt/id :user-9 :user/id :user-9 :user/name "user 9" :user/friends [:user-10]}]
   [::xt/put {:xt/id :user-10 :user/id :user-10 :user/name "user 10" :user/friends [:user-10 :user-9 :user-11]}]
   [::xt/put {:xt/id :user-11 :user/id :user-11 :user/name "user 11" :user/friends [:user-10 :user-12]}]
   [::xt/put {:xt/id :user-12 :user/id :user-12 :user/name "user 12" :user/friends [:user-11 :user-12]}]]
  )

(xt/sync xt-node)

(defonce db_ (r/atom (xt/db xt-node)))

(comment
  (<sub db_ [::user {:user/id :user-1 subs/query-key [:user/name :user/id {:user/friends 1}]}])
  (<sub db_ [::user {:user/id :user-1 subs/query-key [:user/name :user/id {:user/friends 0}]}])
  (<sub db_ [::user {:user/id :user-1 subs/query-key [:user/name :user/id {:user/friends '...}]}])

  (<sub db_ [::user {'keep-walking?         (fn [e]
                                              (println "IN KEEP walking? " e)
                                              (#{"user 1" "user 2"} (:user/name e))
                                              ;(= "user 1" (:user/name e))
                                              )
                     ::subs-keys/walk-style :predicate
                     :user/id               :user-1 subs/query-key [:user/name :user/id {:user/friends 'keep-walking?}]}])

  (xt/pull (xt/db xt-node) [:user/name :user/id {:user/friends '...}] :user-9)
  (xt/pull (xt/db xt-node) [:user/name :user/id {:user/friends ['*]}] :user-9)
  (xt/entity (xt/db xt-node) :user-1)
  ;(<sub xt-node )
  )

;(<sub app [::user {:user/id 1 ::subs/query [:user/id {:user/friends `keep-walking?}]}])


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

;(deftest union-queries-test
;  (testing "to-one union queries"
;    (is (= {:todo/id 1, :todo/author {:bot/name "bot 1", :bot/id 1}}
;          (<sub app [::todo {:todo/id 1 ::subs/query [:todo/id :todo/author]}])))
;    (is (=
;          {:todo/id 2, :todo/author #:user{:id 2, :name "user 2", :friends [[:user/id 2] [:user/id 1] [:user/id 3]]}}
;          (<sub app [::todo {:todo/id 2 ::subs/query [:todo/id :todo/author]}])))
;    (is (= {:todo/id 2, :todo/author {:user/name "user 2"}}
;          (<sub app [::todo {:todo/id 2 ::subs/query [:todo/id {:todo/author {:user/id        [:user/name]
;                                                                              :does-not/exist [:a :b :c]}}]}])))
;    (testing "support for * query"
;      (is (= #:todo{:id 1, :author #:bot{:id 1, :name "bot 1"}} (<sub app [::todo {:todo/id 1 ::subs/query [:todo/id {:todo/author ['*]}]}])))
;      (is (= #:todo{:id 2, :author #:user{:id 2, :name "user 2", :friends [[:user/id 2] [:user/id 1] [:user/id 3]]}}
;            (<sub app [::todo {:todo/id 2 ::subs/query [:todo/id {:todo/author '[*]}]}])))))
;
;  (testing "to-many union queries"
;    (is (=
;          #:list{:items   [#:todo{:id     2, :text "todo 2",
;                                  :author #:user{:id 2, :name "user 2", :friends [#:user{:id 2, :name "user 2", :friends ::subs/cycle}
;                                                                                  #:user{:id 1, :name "user 1", :friends [#:user{:id 2, :name "user 2", :friends ::subs/cycle}]}
;                                                                                  #:user{:id      3, :name "user 3",
;                                                                                         :friends [#:user{:id 2, :name "user 2", :friends ::subs/cycle}
;                                                                                                   #:user{:id      4, :name "user 4",
;                                                                                                          :friends [#:user{:id 3, :name "user 3", :friends ::subs/cycle}
;                                                                                                                    #:user{:id 4, :name "user 4", :friends ::subs/cycle}]}]}]}}
;                           #:comment{:id 1, :text "FIRST COMMENT", :sub-comments [#:comment{:id 2, :text "SECOND COMMENT"}]}],
;                 :members [#:comment{:id 1, :text "FIRST COMMENT"} #:todo{:id 2, :text "todo 2"}]}
;
;          (<sub app [::list {:list/id 1 ::subs/query [{:list/items list-member-q}
;                                                      {:list/members {:comment/id [:comment/id :comment/text] :todo/id [:todo/id :todo/text]}}]}])))
;
;    (testing "unions should only return queried-for branches"
;      (is (= {:list/items []} (<sub app [::list {:list/id 1 ::subs/query [{:list/items {:todo2/id [:todo/id :todo/text]}}]}])))
;      (is (= {:list/items [{:todo/id 2, :todo/text "todo 2"}]}
;            (<sub app [::list {:list/id 1 ::subs/query [{:list/items {:todo/id [:todo/id :todo/text]}}]}]))))))
;
;(deftest plain-join-queries
;
;  (testing "to-one joins"
;    (is (= #:todo{:id 1, :author #:bot{:id 1, :name "bot 1"}, :comment #:comment{:id 1, :text "FIRST COMMENT", :sub-comments [[:comment/id 2]]}}
;          (<sub app [::todo {:todo/id 1 ::subs/query [:todo/id :todo/author :todo/comment]}])))
;    (is (= #:todo{:id 1,, :comment #:comment{:id 1, :text "FIRST COMMENT"}}
;          (<sub app [::todo {:todo/id 1 ::subs/query [:todo/id {:todo/comment [:comment/id :comment/text]}]}]))))
;
;  (testing "to-many joins"
;    (is (= {:todo/id 2} (<sub app [::todo {:todo/id 2 ::subs/query [:todo/id {:todo/comments [:comment/id :comment/text]}]}])))
;    (is (= #:todo{:id 3, :comments [#:comment{:id 1, :text "FIRST COMMENT"} #:comment{:id 3, :text "THIRD COMMENT"}]}
;          (<sub app [::todo {:todo/id 3 ::subs/query [:todo/id {:todo/comments [:comment/id :comment/text]}]}])))
;    (testing "support for '[*]"
;      (is (=
;            {:todo/id       3,
;             :todo/comments [{:comment/sub-comments [[:comment/id 2]], :comment/id 1, :comment/text "FIRST COMMENT"}
;                             {:comment/sub-comments ::subs/missing,
;                              :comment/id           3,
;                              :comment/text         "THIRD COMMENT"}]}
;            (<sub app [::todo {:todo/id 3 ::subs/query [:todo/id {:todo/comments ['*]}]}]))))))
;
;(deftest recursive-join-queries
;  (is (= #:user{:name "user 1", :id 1, :friends [[:user/id 2]]}
;        (<sub app [::user {:user/id 1 subs/query-key [:user/name :user/id {:user/friends 0}]}])))
;
;  (is (= #:user{:name    "user 1", :id 1,
;                :friends [#:user{:name "user 2", :id 2, :friends [[:user/id 2] [:user/id 1] [:user/id 3]]}]}
;        (<sub app [::user {:user/id 1 subs/query-key [:user/name :user/id {:user/friends 1}]}])))
;
;  (testing "handles self-cycle"
;    (is (=
;          {:human/id 1, :human/best-friend [:human/id 1], :human/name "human Y"}
;          (<sub app [::human {:human/id 1 subs/query-key [:human/id :human/best-friend :human/name]}])))
;
;    (is (= {:human/id 1, :human/best-friend ::subs/cycle, :human/name "human Y"}
;          (<sub app [::human {:human/id 1 subs/query-key [:human/id {:human/best-friend '...} :human/name]}])))
;
;    (testing "handles multi-level to-one cycle"
;      (is (=
;            {:human/id          2,
;             :human/best-friend {:human/id          3,
;                                 :human/best-friend {:human/id          1,
;                                                     :human/best-friend :space.matterandvoid.subscriptions.fulcro/cycle,
;                                                     :human/name        "human Y"},
;                                 :human/name        "human Z"},
;             :human/name        "human X"}))
;      (<sub app [::human {:human/id 2 subs/query-key [:human/id {:human/best-friend '...} :human/name]}]))
;
;    (testing "handles finite self-recursive (to-one) cycles"
;      (is (= {:human/id          1,
;              :human/best-friend {:human/id          1,
;                                  :human/best-friend {:human/id 1, :human/best-friend ::subs/cycle, :human/name "human Y"},
;                                  :human/name        "human Y"},
;              :human/name        "human Y"}
;            (<sub app [::human {:human/id 1 subs/query-key [:human/id {:human/best-friend 2} :human/name]}])))))
;
;  (testing "handles to-many recursive cycles"
;    (is (=
;          #:user{:name    "user 1", :id 1,
;                 :friends [#:user{:name    "user 2", :id 2,
;                                  :friends [#:user{:name "user 2", :id 2, :friends ::subs/cycle}
;                                            #:user{:name "user 1", :id 1, :friends ::subs/cycle}
;                                            #:user{:name    "user 3", :id 3,
;                                                   :friends [#:user{:name "user 2", :id 2, :friends ::subs/cycle}
;                                                             #:user{:name    "user 4", :id 4,
;                                                                    :friends [#:user{:name "user 3", :id 3, :friends ::subs/cycle}
;                                                                              #:user{:name "user 4", :id 4, :friends ::subs/cycle}]}]}]}]}
;          (<sub app [::user {:user/id 1 subs/query-key [:user/name :user/id {:user/friends '...}]}])))))
;
;(deftest queries-test
;  (testing "props"
;    (<sub app [::todo {:todo/id 1 subs/query-key [:todo/id :todo/text]}])
;    (<sub app [::todo {:todo/id 1 subs/query-key ['* :todo/text]}])
;    )
;  (testing "entity subscription with no query returns all attributes"
;    (is (= {:list/members [[:comment/id 1] [:todo/id 2]]
;            :list/items   [[:todo/id 2] [:comment/id 1]], :list/name "first list", :list/id 1}
;          (<sub app [::list {:list/id 1}])))))
;
;(comment
;  (<sub app [::list {:list/id 1 ::subs/query [{:list/items list-member-q}
;                                              {:list/members {:comment/id [:comment/id :comment/text] :todo/id [:todo/id :todo/text]}}]}])
;  (<sub app [::todo {:todo/id 1 ::subs/query [:todo/id :todo/author]}])
;  (<sub app [::list {:list/id 1}])
;  (<sub app [::list {:list/id 1}])
;  (<sub app [::list {:list/id 1 ::subs/query [#_:list/name {:list/members {:comment/id [:comment/id :comment/text]
;                                                                           :todo/id    [:todo/id :todo/text]}}]}])
;  (<sub app [::list {:list/id 1 ::subs/query [:list/name {:list/members {:comment/id [:comment/id :comment/text
;                                                                                      {:comment/sub-comments '...}]
;                                                                         :todo/id    [:todo/id :todo/text]}}]}])
;  (<sub app [::list {:list/id 1 ::subs/query [:list/name
;                                              {:list/members {:comment/id [:comment/id :comment/text {:comment/sub-comments '...}] :todo/id [:todo/id :todo/text]}}
;                                              {:list/items
;
;                                               {:comment/id [:comment/id :comment/text {:comment/sub-comments 0}] :todo/id [:todo/id :todo/text]}
;                                               }
;                                              ]}])
;  (<sub app [::list {:list/id 1 ::subs/query [; :list/name
;                                              ;{:list/members (rc/get-query list-member-comp)}
;                                              {:list/items {
;                                                            ;:comment/id [:comment/id :comment/text {:comment/sub-comments 0}]
;                                                            :todo/id [:todo/id :todo/text]}}
;                                              ]}])
;
;  )
