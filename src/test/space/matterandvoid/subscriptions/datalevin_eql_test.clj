(ns space.matterandvoid.subscriptions.datalevin-eql-test
  (:require
    [clojure.test :refer [deftest testing is]]
    [datalevin.core :as d]
    [space.matterandvoid.subscriptions.core :as subs :refer [<sub]]
    [space.matterandvoid.subscriptions.datalevin-eql :as sut]
    [com.fulcrologic.fulcro.raw.components :as rc]
    [space.matterandvoid.subscriptions.impl.reagent-ratom :as r]
    [taoensso.timbre :as log]))

(log/set-level! :debug)

(def schema
  {:user/id              {:db/valueType :db.type/keyword :db/unique :db.unique/identity}
   :user/friends         {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   :user/name            {:db/valueType :db.type/string :db/unique :db.unique/identity}


   :bot/id               {:db/valueType :db.type/keyword :db/unique :db.unique/identity}
   :bot/name             {:db/valueType :db.type/string :db/unique :db.unique/identity}


   :comment/id           {:db/valueType :db.type/keyword :db/unique :db.unique/identity}
   :comment/text         {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :comment/sub-comments {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}

   :list/id              {:db/valueType :db.type/keyword :db/unique :db.unique/identity}
   :list/name            {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :list/members         {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   :list/items           {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}

   :human/id             {:db/valueType :db.type/keyword :db/unique :db.unique/identity}
   :human/name           {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :human/best-friend    {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}

   :todo/id              {:db/valueType :db.type/keyword :db/unique :db.unique/identity}
   :todo/text            {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :todo/author          {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :todo/comment         {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :todo/comments        {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}})

(defonce conn (d/get-conn "/tmp/datalevin/mydb" schema))

(comment
  (d/transact! conn
    [{:user/name "first" :user/id :user-1}
     {:user/name "second" :user/id :user-2}
     {:user/name "third" :user/id :user-3 :user/friends [[:user/id :user-2]]}])

  (d/touch (d/entity (d/db conn) (:db/id (first (:user/friends (d/touch (d/entity (d/db conn) [:user/id :user-3]))))))))


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

(d/transact! conn
  [{:comment/id :comment-1 :comment/text "FIRST COMMENT" :comment/sub-comments [:comment/id :comment-2]}
   {:comment/id :comment-2 :comment/text "SECOND COMMENT"}
   {:comment/id :comment-3 :comment/text "THIRD COMMENT"}
   {:list/id      :list-1 :list/name "first list"
    :list/members [[:comment/id :comment-1] [:todo/id :todo-2]]
    :list/items   [[:todo/id :todo-2] [:comment/id :comment-1]]}

   ;; to-one cycle
   {:human/id :human-1 :human/name "human Y" :human/best-friend [:human/id :humnan-1]}
   {:human/id :human-2 :human/name "human X" :human/best-friend [:human/id :human-3]}
   {:human/id :human-3 :human/name "human Z" :human/best-friend [:human/id :human-1]}

   ;; to-many cycle
   {:user/id :user-7 :user/name "user 7"}
   {:user/id :user-6 :user/name "user 6" :user/friends [[:user/id :user-7]]}
   {:user/id :user-5 :user/name "user 5" :user/friends [[:user/id :user-6] [:user/id :user-7]]}
   {:user/id :user-2 :user/name "user 2" :user/friends [[:user/id :user-2] [:user/id :user-1] [:user/id :user-3] [:user/id :user-5]]}
   {:user/id :user-1 :user/name "user 1" :user/friends [[:user/id :user-2]]}
   {:user/id :user-4 :user/name "user 4" :user/friends [[:user/id :user-3] [:user/id :user-4]]}
   {:user/id :user-3 :user/name "user 3" :user/friends [[:user/id :user-2] [:user/id :user-4]]}

   {:bot/id :bot-1 :bot/name "bot 1"}
   ;; union queries
   {:todo/id :todo-1 :todo/text "todo 1" :todo/author [:bot/id :bot-1] :todo/comment [:comment/id :comment-1]}
   {:todo/id :todo-2 :todo/text "todo 2" :todo/author [:user/id :user-2]}
   {:todo/id :todo-3 :todo/text "todo 3" :todo/comments [[:comment/id :comment-1] [:comment/id :comment-3]]}

   {:user/id :user-9 :user/name "user 9" :user/friends ["user-10"]}
   {:db/id "user-10" :user/id :user-10 :user/name "user 10" :user/friends [[:user/id :user-10] [:user/id :user-9] "user-11"]}
   {:db/id "user-11" :user/id :user-11 :user/name "user 11" :user/friends [[:user/id :user-10] "user-12"]}
   {:db/id "user-12" :user/id :user-12 :user/name "user 12" :user/friends [[:user/id :user-11] [:user/id :user-12]]}])

(d/entity (d/db conn) [:user/id :user-12])
(d/datoms (d/db conn) :eav)

(defonce db_ (r/atom (d/db conn)))

(comment
  (d/touch (d/entity db 1))
  (d/touch (d/entity db 2)) ; user-2
  (d/touch (d/entity db 3)) ; user-3
  (d/touch (d/entity db 11)) ; user-7
  (d/touch (d/entity db 13)) ; user-5
  (d/touch (d/entity db 13))
  (d/touch (d/entity db {:db/id 2}))
  (try
    (d/entity @db [:user/id #{{:db/id 2}}])
    (catch ClassCastException e))
  (<sub db_ [::user {:user/id :user-1 sut/query-key [:user/name :user/id {:user/friends 4}]}])
  (<sub db_ [::user {:user/id :user-1 sut/query-key [:user/name :user/id {:user/friends 2}]}])
  (d/touch (d/entity db [:user/id :user-12]))
  (into {} (d/entity db [:user/id :user-12]))
  (<sub db_ [::user {'keep-walking? (fn [e]
                                      (println "IN KEEP walking?  " e)
                                      (#{"user 1" "user 2"} (:user/name e))
                                      ;(= "user 1" (:user/name e))
                                      )
                     :user/id       :user-1
                     sut/query-key  [:user/name :user/id {(list :user/friends {sut/walk-fn-key 'keep-walking?}) '...}]}])
  )
