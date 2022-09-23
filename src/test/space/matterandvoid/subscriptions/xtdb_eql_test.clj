(ns space.matterandvoid.subscriptions.xtdb-eql-test
  (:require
    [space.matterandvoid.subscriptions.xtdb-eql :as sut]
    [space.matterandvoid.subscriptions.core :as subs :refer [<sub]]
    [space.matterandvoid.subscriptions.impl.reagent-ratom :as r]
    [com.fulcrologic.fulcro.raw.components :as rc]
    [taoensso.timbre :as log]
    [edn-query-language.core :as eql]
    [xtdb.api :as xt]
    [clojure.test :refer [deftest is testing]]
    [clojure.string :as str]))

(log/set-level! :debug)
(set! *print-namespace-maps* false)

(defonce xt-node (xt/start-node {}))

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

(run! sut/register-component-subs! [user-comp bot-comp comment-comp todo-comp list-comp human-comp])

(xt/submit-tx xt-node
  [[::xt/put {:xt/id :comment-1 :comment/id :comment-1 :comment/text "FIRST COMMENT" :comment/sub-comments [[:comment/id :comment-2]]}]
   [::xt/put {:xt/id :comment-2 :comment/id :comment-2 :comment/text "SECOND COMMENT"}]
   [::xt/put {:xt/id :comment-3 :comment/id :comment-3 :comment/text "THIRD COMMENT"}]
   [::xt/put {:xt/id        :list-1 :list/id :list-1 :list/name "first list"
              :list/members [[:comment/id :comment-1] [:todo/id :todo-2]]
              :list/items   [[:todo/id :todo-2] [:comment/id :comment-1]]}]

   ;; to-one cycle
   [::xt/put {:xt/id :human-1 :human/id :human-1 :human/name "human Y" :human/best-friend [:human/id :human-1]}]
   [::xt/put {:xt/id :human-2 :human/id :human-2 :human/name "human X" :human/best-friend [:human/id :human-3]}]
   [::xt/put {:xt/id :human-3 :human/id :human-3 :human/name "human Z" :human/best-friend [:human/id :human-1]}]

   ;; to-many cycle
   [::xt/put {:xt/id :user-1 :user/id :user-1 :user/name "user 1" :user/friends [[:user/id :user-2]]}]
   [::xt/put {:xt/id :user-2 :user/id :user-2 :user/name "user 2" :user/friends [[:user/id :user-2] [:user/id :user-1] [:user/id :user-3] [:user/id :user-5]]}]
   [::xt/put {:xt/id :user-3 :user/id :user-3 :user/name "user 3" :user/friends [[:user/id :user-2] [:user/id :user-4]]}]
   [::xt/put {:xt/id :user-4 :user/id :user-4 :user/name "user 4" :user/friends [[:user/id :user-3] [:user/id :user-4]]}]
   [::xt/put {:xt/id :user-5 :user/id :user-5 :user/name "user 5" :user/friends [[:user/id :user-6] [:user/id :user-7]]}]
   [::xt/put {:xt/id :user-6 :user/id :user-6 :user/name "user 6" :user/friends [[:user/id :user-7]]}]
   [::xt/put {:xt/id :user-7 :user/id :user-7 :user/name "user 7"}]

   [::xt/put {:xt/id :bot-1 :bot/id :bot-1 :bot/name "bot 1"}]
   ;; union queries
   [::xt/put {:xt/id :todo-1 :todo/id :todo-1 :todo/text "todo 1" :todo/author [:bot/id :bot-1] :todo/comment [:comment/id :comment-1]}]
   [::xt/put {:xt/id :todo-2 :todo/id :todo-2 :todo/text "todo 2" :todo/author [:user/id :user-2]}]
   [::xt/put {:xt/id :todo-3 :todo/id :todo-3 :todo/text "todo 3" :todo/comments [[:comment/id :comment-1] [:comment/id :comment-3]]}]

   [::xt/put {:xt/id :user-9 :user/id :user-9 :user/name "user 9" :user/friends [:user-10]}]
   [::xt/put {:xt/id :user-10 :user/id :user-10 :user/name "user 10" :user/friends [:user-10 :user-9 :user-11]}]
   [::xt/put {:xt/id :user-11 :user/id :user-11 :user/name "user 11" :user/friends [:user-10 :user-12]}]
   [::xt/put {:xt/id :user-12 :user/id :user-12 :user/name "user 12" :user/friends [:user-11 :user-12]}]]
  )

(xt/sync xt-node)

(xt/submit-tx xt-node
  ;; to-many cycle with plain id refs
  [[::xt/put {:xt/id :user-1-a :user/id :user-1-a :user/name "user 1" :user/friends [:user-2-a]}]
   [::xt/put {:xt/id :user-2-a :user/id :user-2-a :user/name "user 2" :user/friends [:user-2-a :user-1-a :user-3-a :user-5-a]}]
   [::xt/put {:xt/id :user-3-a :user/id :user-3-a :user/name "user 3" :user/friends [:user-2-a :user-4-a]}]
   [::xt/put {:xt/id :user-4-a :user/id :user-4-a :user/name "user 4" :user/friends [:user-3-a :user-4-a]}]
   [::xt/put {:xt/id :user-5-a :user/id :user-5-a :user/name "user 5" :user/friends [:user-6-a :user-7-a]}]
   [::xt/put {:xt/id :user-6-a :user/id :user-6-a :user/name "user 6" :user/friends [:user-7-a]}]
   [::xt/put {:xt/id :user-7-a :user/id :user-7-a :user/name "user 7"}]])

(xt/sync xt-node)

(defonce db_ (r/atom (xt/db xt-node)))
;(reset! db_ (xt/db xt-node))

(comment
  (<sub db_ [::user {:user/id :user-1-a sut/query-key [:user/name :user/id {:user/friends 1}]}])
  (<sub db_ [::user {:user/id :user-2-a sut/query-key [:user/name :user/id {:user/friends 1}]}])
  (<sub db_ [::user {:user/id :user-2 sut/query-key [:user/name :user/id {:user/friends 1}]}])
  (<sub db_ [::user {:user/id :user-1 sut/query-key [:user/name :user/id {:user/friends 1}]}])
  (<sub db_ [::user {:user/id :user-1 sut/query-key [:user/name :user/id {:user/friends 0}]}])
  (<sub db_ [::user {:user/id :user-1 sut/query-key [:user/name :user/id {:user/friends '...}]}])

  ;; todo repalce this with EQL parameters instead of custom EQL fork.
  [:a '{(:recursive-join {:continue? some.ns/walk?}) ...}]

  (eql/query->ast
    [:a '{(:recursive-join {:continue? some.ns/walk?}) ...}])

  (<sub db_ [::user {'keep-walking? (fn [e] (println "IN KEEP walking?  " e) (#{"user 1" "user 2"} (:user/name e)))
                     :user/id       :user-1
                     sut/query-key  [:user/name :user/id {(list :user/friends {sut/walk-fn-key 'keep-walking?}) '...}]}])

  (<sub db_ [::user {'get-friends (fn [e]
                                    (println "IN GET FRIENDS " e)
                                    (let [friends (map (fn [[_ f-id]] (xt/entity (xt/db xt-node) f-id)) (:user/friends e))]
                                      (println "friends: " friends)
                                      ;; stop keeps the entity but does not recur on it, vs removing it completely from the
                                      ;; result set.
                                      {:stop   (mapv (fn [{:user/keys [id]}] [:user/id id])
                                                 (filter (fn [{:user/keys [name]}] (= name "user 3")) friends))
                                       :expand (mapv (fn [{:user/keys [id]}] [:user/id id])
                                                 (remove
                                                   (fn [{:user/keys [name]}] (= name "user 3")) friends))}))

                     :user/id     :user-1 sut/query-key [:user/name :user/id
                                                         {(list :user/friends {sut/walk-fn-key 'get-friends}) '...}]}])

  ;This returns:

  {:user/name    "user 1",
   :user/id      :user-1,
   :user/friends [{:user/name    "user 2",
                   :user/id      :user-2,
                   :user/friends [{:user/name    "user 2",
                                   :user/id      :user-2,
                                   :user/friends :space.matterandvoid.subscriptions/cycle}
                                  {:user/name    "user 1",
                                   :user/id      :user-1,
                                   :user/friends :space.matterandvoid.subscriptions/cycle}
                                  {:user/id      :user-3,
                                   :user/name    "user 3",
                                   :user/friends [[:user/id :user-2] [:user/id :user-4]],
                                   :xt/id        :user-3}]}]}


  (xt/pull (xt/db xt-node) [:user/name :user/id {:user/friends '...}] :user-9)
  (xt/pull (xt/db xt-node) [:user/name :user/id {:user/friends ['*]}] :user-9)
  (xt/entity (xt/db xt-node) :user-1)
  )

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
;;   - to-one0
;;   - to-many
(<sub db_ [::todo {:todo/id :todo-1 sut/query-key [:todo/id :todo/author]}])
(deftest union-queries-test
  (testing "to-one union queries"
    (is (= {:todo/id :todo-1, :todo/author {:bot/name "bot 1", :bot/id :bot-1}}
          (<sub db_ [::todo {:todo/id :todo-1 sut/query-key [:todo/id :todo/author]}])))
    (is (=
          {:todo/id :todo-2, :todo/author {:user/id :user-2, :user/name "user 2", :user/friends [[:user/id :user-2] [:user/id :user-1] [:user/id :user-3] [:user/id :user-5]]}}
          (<sub db_ [::todo {:todo/id :todo-2 sut/query-key [:todo/id :todo/author]}])))
    (is (= {:todo/id :todo-2, :todo/author {:user/name "user 2"}}
          (<sub db_ [::todo {:todo/id :todo-2 sut/query-key [:todo/id {:todo/author {:user/id        [:user/name]
                                                                                     :does-not/exist [:a :b :c]}}]}])))
    (testing "support for * query"
      (is (= #:todo{:id :todo-1, :author {:bot/id :bot-1 :bot/name "bot 1"}} (<sub db_ [::todo {:todo/id :todo-1 sut/query-key [:todo/id {:todo/author ['*]}]}])))
      (is (= #:todo{:id :todo-2, :author {:user/id :user-2, :user/name "user 2", :user/friends [[:user/id :user-2] [:user/id :user-1] [:user/id :user-3] [:user/id :user-5]]}}
            (<sub db_ [::todo {:todo/id :todo-2 sut/query-key [:todo/id {:todo/author '[*]}]}])))))

  (testing "to-many union queries"
    (is (=
          {:list/items   [{:todo/id     :todo-2,
                           :todo/text   "todo 2",
                           :todo/author {:user/id      :user-2,
                                         :user/name    "user 2",
                                         :user/friends [{:user/id :user-2, :user/name "user 2", :user/friends [[:user/id :user-2] [:user/id :user-1] [:user/id :user-3] [:user/id :user-5]]}
                                                        {:user/id :user-1, :user/name "user 1", :user/friends [{:user/id :user-2, :user/name "user 2", :user/friends [[:user/id :user-2] [:user/id :user-1] [:user/id :user-3] [:user/id :user-5]]}]}
                                                        {:user/id :user-3, :user/name "user 3", :user/friends [{:user/id :user-2, :user/name "user 2", :user/friends [[:user/id :user-2] [:user/id :user-1] [:user/id :user-3] [:user/id :user-5]]}
                                                                                                               {:user/id :user-4, :user/name "user 4", :user/friends [{:user/id :user-3, :user/name "user 3", :user/friends [[:user/id :user-2] [:user/id :user-4]]}
                                                                                                                                                                      {:user/id :user-4, :user/name "user 4", :user/friends [[:user/id :user-3] [:user/id :user-4]]}]}]}
                                                        {:user/id :user-5, :user/name "user 5", :user/friends [{:user/id :user-6, :user/name "user 6", :user/friends [{:user/id :user-7, :user/name "user 7"}]} {:user/id :user-7, :user/name "user 7"}]}]}}
                          {:comment/id :comment-1, :comment/text "FIRST COMMENT", :comment/sub-comments [{:comment/id :comment-2, :comment/text "SECOND COMMENT"}]}],
           :list/members [{:comment/id :comment-1, :comment/text "FIRST COMMENT"} {:todo/id :todo-2, :todo/text "todo 2"}]}
          (<sub db_ [::list {:list/id :list-1 sut/query-key [{:list/items list-member-q}
                                                             {:list/members {:comment/id [:comment/id :comment/text] :todo/id [:todo/id :todo/text]}}]}])))

    (testing "unions should only return queried-for branches"
      (is (= {:list/items []} (<sub db_ [::list {:list/id :list-1 sut/query-key [{:list/items {:todo2/id [:todo/id :todo/text]}}]}])))
      (is (= {:list/items [{:todo/id :todo-2, :todo/text "todo 2"}]}
            (<sub db_ [::list {:list/id :list-1 sut/query-key [{:list/items {:todo/id [:todo/id :todo/text]}}]}]))))))

(deftest plain-join-queries

  (testing "to-one joins"
    (is (= {:todo/id      :todo-1,
            :todo/author  {:bot/id :bot-1, :bot/name "bot 1"},
            :todo/comment {:comment/id           :comment-1, :comment/text "FIRST COMMENT",
                           :comment/sub-comments [[:comment/id :comment-2]]}}
          (<sub db_ [::todo {:todo/id :todo-1 sut/query-key [:todo/id :todo/author :todo/comment]}])))
    (is (= #:todo{:id :todo-1 :comment #:comment{:id :comment-1, :text "FIRST COMMENT"}}
          (<sub db_ [::todo {:todo/id :todo-1 sut/query-key [:todo/id {:todo/comment [:comment/id :comment/text]}]}]))))

  (testing "to-many joins"
    (is (= {:todo/id :todo-2} (<sub db_ [::todo {:todo/id :todo-2 sut/query-key [:todo/id {:todo/comments [:comment/id :comment/text]}]}])))
    (is (= #:todo{:id :todo-3, :comments [#:comment{:id :comment-1, :text "FIRST COMMENT"} #:comment{:id :comment-3, :text "THIRD COMMENT"}]}
          (<sub db_ [::todo {:todo/id :todo-3 sut/query-key [:todo/id {:todo/comments [:comment/id :comment/text]}]}])))
    (testing "support for '[*]"
      (is (=
            {:todo/id       :todo-3,
             :todo/comments [{:comment/sub-comments [[:comment/id :comment-2]],
                              :xt/id                :comment-1
                              :comment/id           :comment-1, :comment/text "FIRST COMMENT"}
                             {:comment/sub-comments sut/missing-val
                              :xt/id                :comment-3
                              :comment/id           :comment-3,
                              :comment/text         "THIRD COMMENT"}]}
            (<sub db_ [::todo {:todo/id :todo-3 sut/query-key [:todo/id {:todo/comments ['*]}]}]))))))

(deftest recursive-join-queries
  (is (= #:user{:name "user 1", :id :user-1, :friends [[:user/id :user-2]]}
        (<sub db_ [::user {:user/id :user-1 sut/query-key [:user/name :user/id {:user/friends 0}]}])))

  (is (= #:user{:name    "user 1", :id :user-1,
                :friends [#:user{:name "user 2", :id :user-2, :friends [[:user/id :user-2] [:user/id :user-1] [:user/id :user-3] [:user/id :user-5]]}]}
        (<sub db_ [::user {:user/id :user-1 sut/query-key [:user/name :user/id {:user/friends 1}]}])))

  (testing "handles self-cycle"
    (is (=
          {:human/id :human-1, :human/best-friend [:human/id :human-1], :human/name "human Y"}
          (<sub db_ [::human {:human/id :human-1 sut/query-key [:human/id :human/best-friend :human/name]}])))

    (is (= {:human/id :human-1, :human/best-friend [:human/id :human-1], :human/name "human Y"}
          (<sub db_ [::human {:human/id :human-1 sut/query-key [:human/id {:human/best-friend '...} :human/name]}])))

    (testing "handles multi-level to-one cycle"
      (is (=
            {:human/id          2,
             :human/best-friend {:human/id          3,
                                 :human/best-friend {:human/id          1,
                                                     :human/best-friend :space.matterandvoid.subscriptions.fulcro/cycle,
                                                     :human/name        "human Y"},
                                 :human/name        "human Z"},
             :human/name        "human X"})
        (<sub db_ [::human {:human/id 2 sut/query-key [:human/id {:human/best-friend '...} :human/name]}])))

    (testing "handles finite self-recursive (to-one) cycles"
      (is (= {:human/id          :human-1,
              :human/best-friend {:human/id          :human-1,
                                  :human/best-friend [:human/id :human-1],
                                  :human/name        "human Y"},
              :human/name        "human Y"}
            (<sub db_ [::human {:human/id :human-1 sut/query-key [:human/id {:human/best-friend 3} :human/name]}])))))

  (testing "handles to-many recursive cycles"
    (is (=
          {:user/name    "user 1",
           :user/id      :user-1,
           :user/friends [{:user/name "user 2", :user/id :user-2, :user/friends
                           [{:user/name "user 2", :user/id :user-2, :user/friends [[:user/id :user-2] [:user/id :user-1] [:user/id :user-3] [:user/id :user-5]]}
                            {:user/name "user 1", :user/id :user-1, :user/friends [[:user/id :user-2]]}
                            {:user/name    "user 3",
                             :user/id      :user-3,
                             :user/friends [{:user/name "user 2", :user/id :user-2, :user/friends [[:user/id :user-2] [:user/id :user-1] [:user/id :user-3] [:user/id :user-5]]}
                                            {:user/name "user 4", :user/id :user-4, :user/friends [{:user/name "user 3", :user/id :user-3, :user/friends [[:user/id :user-2] [:user/id :user-4]]}
                                                                                                   {:user/name "user 4", :user/id :user-4, :user/friends [[:user/id :user-3] [:user/id :user-4]]}]}]}
                            {:user/name    "user 5",
                             :user/id      :user-5,
                             :user/friends [{:user/name "user 6", :user/id :user-6, :user/friends [{:user/name "user 7", :user/id :user-7}]} {:user/name "user 7", :user/id :user-7}]}]}]}

          (<sub db_ [::user {:user/id :user-1 sut/query-key [:user/name :user/id {:user/friends '...}]}])))))

(deftest queries-test
  (testing "props"
    (is (= {:todo/id :todo-1, :todo/text "todo 1"})
      (<sub db_ [::todo {:todo/id :todo-1 sut/query-key [:todo/id :todo/text]}]))
    (is (=
          {:todo/comments sut/missing-val
           :todo/author   [:bot/id :bot-1],
           :todo/comment  [:comment/id :comment-1],
           :todo/text     "todo 1",
           :todo/id       :todo-1})
      (<sub db_ [::todo {:todo/id :todo-1 sut/query-key ['* :todo/text]}])))
  (testing "entity subscription with no query returns all attributes"
    (is (= {:list/items   [{:todo/comments :space.matterandvoid.subscriptions.impl.eql-queries/missing,
                            :todo/author   {:user/friends [[:user/id :user-2] [:user/id :user-1] [:user/id :user-3] [:user/id :user-5]],
                                            :user/name    "user 2",
                                            :user/id      :user-2},
                            :todo/comment  sut/missing-val,
                            :todo/text     "todo 2", :todo/id :todo-2}
                           {:comment/sub-comments [[:comment/id :comment-2]], :comment/id :comment-1, :comment/text "FIRST COMMENT"}],
            :list/members [{:comment/sub-comments [[:comment/id :comment-2]],
                            :comment/id           :comment-1,
                            :comment/text         "FIRST COMMENT"}
                           {:todo/comments sut/missing-val,
                            :todo/author   {:user/friends [[:user/id :user-2]
                                                           [:user/id :user-1]
                                                           [:user/id :user-3]
                                                           [:user/id :user-5]],
                                            :user/name    "user 2", :user/id :user-2},
                            :todo/comment  sut/missing-val
                            :todo/text     "todo 2", :todo/id :todo-2}],
            :list/name    "first list",
            :list/id      :list-1}
          (<sub db_ [::list {:list/id :list-1}])))))

(deftest walking-test
  (testing "hashmap expansion"
    (let [out (<sub db_ [::user {`get-friends (fn [e]
                                                (let [friends (map (fn [[_ f-id]] (xt/entity (xt/db xt-node) f-id)) (:user/friends e))]
                                                  ;; stop keeps the entity but does not recur on it, vs removing it completely from the
                                                  ;; result set.
                                                  {:stop   (mapv (fn [{:user/keys [id]}] [:user/id id]) (filter (fn [{:user/keys [name]}] (= name "user 3")) friends))
                                                   :expand (mapv (fn [{:user/keys [id]}] [:user/id id]) (remove (fn [{:user/keys [name]}] (= name "user 3")) friends))}))

                                 :user/id     :user-1 sut/query-key [:user/name :user/id
                                                                     {(list :user/friends {sut/walk-fn-key `get-friends}) '...}]}])]
      (is (= {:user/name    "user 1",
              :user/id      :user-1,
              :user/friends [{:user/name    "user 2",
                              :user/id      :user-2,
                              :user/friends [{:user/name    "user 2",
                                              :user/id      :user-2,
                                              :user/friends [[:user/id :user-2] [:user/id :user-1] [:user/id :user-3] [:user/id :user-5]]}
                                             {:user/name "user 1", :user/id :user-1, :user/friends [[:user/id :user-2]]}
                                             {:user/name    "user 5",
                                              :user/id      :user-5,
                                              :user/friends [{:user/name "user 6", :user/id :user-6, :user/friends [{:user/name "user 7", :user/id :user-7}]} {:user/name "user 7", :user/id :user-7}]}
                                             {:user/id :user-3, :user/name "user 3", :user/friends [[:user/id :user-2] [:user/id :user-4]], :xt/id :user-3}]}]} out))))

  (testing "collection expansion"
    (let [out1 (<sub db_ [::user {`get-friends (fn [e]
                                                 (let [friends (map (fn [[_ f-id]] (xt/entity (xt/db xt-node) f-id)) (:user/friends e))]
                                                   (->> friends
                                                     (filter (fn [{:user/keys [name]}] (or (= name "user 3") (= name "user 2") (= name "user 1"))))
                                                     (mapv (fn [{:user/keys [id]}] [:user/id id])))))
                                  :user/id     :user-1 sut/query-key [:user/name :user/id
                                                                      {(list :user/friends {sut/walk-fn-key `get-friends}) '...}]}])
          out2 (<sub db_ [::user {`get-friends (fn [e]
                                                 (let [friends (map (fn [[_ f-id]] (xt/entity (xt/db xt-node) f-id)) (:user/friends e))]
                                                   (->> friends
                                                     (filter (fn [{:user/keys [name]}] (or (= name "user 2") (= name "user 1"))))
                                                     (mapv (fn [{:user/keys [id]}] [:user/id id])))))
                                  :user/id     :user-1 sut/query-key [:user/name :user/id
                                                                      {(list :user/friends {sut/walk-fn-key `get-friends}) '...}]}])]

      (is (= {:user/name    "user 1", :user/id :user-1,
              :user/friends [{:user/name    "user 2", :user/id :user-2,
                              :user/friends [{:user/name    "user 2", :user/id :user-2,
                                              :user/friends [[:user/id :user-2] [:user/id :user-1] [:user/id :user-3] [:user/id :user-5]]}
                                             {:user/name "user 1", :user/id :user-1, :user/friends [[:user/id :user-2]]}]}]}
            out2))

      (is (= {:user/name    "user 1", :user/id :user-1,
              :user/friends [{:user/name    "user 2", :user/id :user-2,
                              :user/friends [{:user/name    "user 2", :user/id :user-2,
                                              :user/friends [[:user/id :user-2] [:user/id :user-1] [:user/id :user-3] [:user/id :user-5]]}
                                             {:user/name "user 1", :user/id :user-1, :user/friends [[:user/id :user-2]]}
                                             {:user/name    "user 3", :user/id :user-3,
                                              :user/friends [{:user/name    "user 2", :user/id :user-2,
                                                              :user/friends [[:user/id :user-2] [:user/id :user-1] [:user/id :user-3] [:user/id :user-5]]}]}]}]}
            out1))))

  (testing "truthy/falsey expansion"
    (let [out (<sub db_ [::user {`keep-walking? (fn [e] (#{"user 1" "user 2"} (:user/name e))) :user/id :user-1
                                 sut/query-key  [:user/name :user/id {(list :user/friends {sut/walk-fn-key `keep-walking?}) '...}]}])]
      (is (= {:user/name    "user 1",
              :user/id      :user-1,
              :user/friends [{:user/name    "user 2",
                              :user/id      :user-2,
                              :user/friends [{:user/name    "user 2",
                                              :user/id      :user-2,
                                              :user/friends [[:user/id :user-2]
                                                             [:user/id :user-1]
                                                             [:user/id :user-3]
                                                             [:user/id :user-5]]}
                                             {:user/name "user 1", :user/id :user-1, :user/friends [[:user/id :user-2]]}
                                             {:user/name    "user 3",
                                              :user/id      :user-3,
                                              :user/friends [[:user/id :user-2] [:user/id :user-4]]}
                                             {:user/name    "user 5",
                                              :user/id      :user-5,
                                              :user/friends [[:user/id :user-6] [:user/id :user-7]]}]}]}
            out)))))

(deftest transform-test
  (testing "transform a map"
    (let [out1 (<sub db_ [::user {`upper-case   (fn [e] (update e :user/name str/upper-case))
                                  :user/id      :user-4
                                  sut/query-key [:user/name :user/id {(list :user/friends {sut/xform-fn-key `upper-case}) 0}]}])
          out2 (<sub db_ [::user {`upper-case   (fn [e] (update e :user/name str/upper-case))
                                  :user/id      :user-4
                                  sut/query-key [:user/name :user/id {(list :user/friends {sut/xform-fn-key `upper-case}) 1}]}])]
      (is (= {:user/name "user 4", :user/id :user-4, :user/friends [[:user/id :user-3] [:user/id :user-4]]} out1))
      (is (= {:user/name    "user 4",
              :user/id      :user-4,
              :user/friends [{:user/name "USER 3", :user/id :user-3, :user/friends [[:user/id :user-2] [:user/id :user-4]]}
                             {:user/name "USER 4", :user/id :user-4, :user/friends [[:user/id :user-3] [:user/id :user-4]]}]}
            out2))))

  (testing "replace the entity"
    (let [out1 (<sub db_ [::user {`upper-case   (fn [_] "REPLACED")
                                  :user/id      :user-4
                                  sut/query-key [:user/name :user/id {(list :user/friends {sut/xform-fn-key `upper-case}) 1}]}])]
      (is (= {:user/name "user 4", :user/id :user-4, :user/friends ["REPLACED" "REPLACED"]} out1)))))

;(comment
;  (<sub db_ [::list {:list/id 1 ::subs/query [{:list/items list-member-q}
;                                              {:list/members {:comment/id [:comment/id :comment/text] :todo/id [:todo/id :todo/text]}}]}])
;  (<sub db_ [::todo {:todo/id 1 ::subs/query [:todo/id :todo/author]}])
;  (<sub db_ [::list {:list/id 1}])
;  (<sub db_ [::list {:list/id 1}])
;  (<sub db_ [::list {:list/id 1 ::subs/query [#_:list/name {:list/members {:comment/id [:comment/id :comment/text]
;                                                                           :todo/id    [:todo/id :todo/text]}}]}])
;  (<sub db_ [::list {:list/id 1 ::subs/query [:list/name {:list/members {:comment/id [:comment/id :comment/text
;                                                                                      {:comment/sub-comments '...}]
;                                                                         :todo/id    [:todo/id :todo/text]}}]}])
;  (<sub db_ [::list {:list/id 1 ::subs/query [:list/name
;                                              {:list/members {:comment/id [:comment/id :comment/text {:comment/sub-comments '...}] :todo/id [:todo/id :todo/text]}}
;                                              {:list/items
;
;                                               {:comment/id [:comment/id :comment/text {:comment/sub-comments 0}] :todo/id [:todo/id :todo/text]}
;                                               }
;                                              ]}])
;  (<sub db_ [::list {:list/id 1 ::subs/query [; :list/name
;                                              ;{:list/members (rc/get-query list-member-comp)}
;                                              {:list/items {
;                                                            ;:comment/id [:comment/id :comment/text {:comment/sub-comments 0}]
;                                                            :todo/id [:todo/id :todo/text]}}
;                                              ]}])
;
;  )
