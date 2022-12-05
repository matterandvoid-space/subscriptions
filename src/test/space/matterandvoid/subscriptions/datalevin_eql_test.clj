(ns space.matterandvoid.subscriptions.datalevin-eql-test
  (:require
    [clojure.test :refer [deftest testing is use-fixtures]]
    [clojure.string :as str]
    [datalevin.core :as d]
    [space.matterandvoid.subscriptions.core :refer [<sub]]
    [space.matterandvoid.subscriptions.datalevin-eql :as sut]
    [space.matterandvoid.subscriptions.impl.reagent-ratom :as r]
    [taoensso.timbre :as log]))

(log/set-level! :error)
(set! *print-namespace-maps* false)

(def schema
  {:user/id              {:db/valueType :db.type/keyword :db/unique :db.unique/identity}
   :user/friends         {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   :user/name            {:db/valueType :db.type/string :db/unique :db.unique/identity}

   :bot/id               {:db/valueType :db.type/keyword :db/unique :db.unique/identity}
   :bot/name             {:db/valueType :db.type/string :db/unique :db.unique/identity}

   :comment/id           {:db/valueType :db.type/keyword :db/unique :db.unique/identity}
   :comment/text         {:db/valueType :db.type/string}
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

(def conn (d/get-conn (str "/tmp/datalevin/" (random-uuid)) schema))

(def user-comp (sut/nc {:query [:user/id :user/name {:user/friends '...}] :name ::user :ident :user/id}))
(def bot-comp (sut/nc {:query [:bot/id :bot/name] :name ::bot :ident :bot/id}))
(def human-comp (sut/nc {:query [:human/id :human/name {:human/best-friend 1}] :name ::human :ident :human/id}))
(def author-comp (sut/nc {:query {:bot/id  (sut/get-query bot-comp)
                                  :user/id (sut/get-query user-comp)}
                          :name  ::author}))
(def comment-comp (sut/nc {:query [:comment/id :comment/text {:comment/sub-comments '...}] :name ::comment :ident :comment/id}))
(def todo-comp (sut/nc {:query [:todo/id :todo/text {:todo/comment (sut/get-query comment-comp)}
                                {:todo/comments (sut/get-query comment-comp)}
                                {:todo/author (sut/get-query author-comp)}] :name ::todo :ident :todo/id}))
(def todo-q (sut/get-query todo-comp))
(def list-member-comp (sut/nc {:query {:comment/id (sut/get-query comment-comp) :todo/id todo-q} :name ::list-member}))
(def list-member-q (sut/get-query list-member-comp))
(def list-comp (sut/nc {:ident :list/id :name ::list
                        :query [:list/id :list/name
                                {:list/items (sut/get-query list-member-comp)}
                                {:list/members (sut/get-query list-member-comp)}]}))

;(reset! subs.impl/handler-registry_ {})
;(subs/clear-subscription-cache! nil)
(run! sut/register-component-subs! [user-comp bot-comp comment-comp todo-comp list-comp human-comp])

(d/transact! conn
  [{:comment/id :comment-1 :comment/text "FIRST COMMENT" :comment/sub-comments ["comment-2"]}
   {:db/id "comment-2" :comment/id :comment-2 :comment/text "SECOND COMMENT"}
   {:comment/id :comment-3 :comment/text "THIRD COMMENT"}

   ;; to-one cycle
   {:db/id "human-1" :human/id :human-1 :human/name "human Y" :human/best-friend "human-1"}
   {:human/id :human-2 :human/name "human X" :human/best-friend "human-3"}
   {:db/id "human-3" :human/id :human-3 :human/name "human Z" :human/best-friend [:human/id :human-1]}

   ;; to-many cycle
   {:user/id :user-7 :user/name "user 7"}
   {:user/id :user-6 :user/name "user 6" :user/friends [[:user/id :user-7]]}
   {:user/id :user-5 :user/name "user 5" :user/friends [[:user/id :user-6] [:user/id :user-7]]}
   {:db/id -2 :user/id :user-2 :user/name "user 2" :user/friends [-2 -1 -3 [:user/id :user-5]]}
   {:db/id -1 :user/id :user-1 :user/name "user 1" :user/friends [-2]}
   {:db/id -4 :user/id :user-4 :user/name "user 4" :user/friends [-3 [:user/id :user-4]]}
   {:db/id -3 :user/id :user-3 :user/name "user 3" :user/friends [[:user/id :user-2] [:user/id :user-4]]}

   {:todo/id :todo-2 :todo/text "todo 2" :todo/author [:user/id :user-2]}
   {:list/id      :list-1 :list/name "first list"
    :list/members [[:comment/id :comment-1] [:todo/id :todo-2]]
    :list/items   [[:todo/id :todo-2] [:comment/id :comment-1]]}

   {:bot/id :bot-1 :bot/name "bot 1"}
   ;; union queries
   {:todo/id :todo-1 :todo/text "todo 1" :todo/author [:bot/id :bot-1] :todo/comment [:comment/id :comment-1]}
   {:todo/id :todo-3 :todo/text "todo 3" :todo/comments [[:comment/id :comment-1] [:comment/id :comment-3]]}

   {:user/id :user-9 :user/name "user 9" :user/friends ["user-10"]}
   {:db/id "user-10" :user/id :user-10 :user/name "user 10" :user/friends [[:user/id :user-10] [:user/id :user-9] "user-11"]}
   {:db/id "user-11" :user/id :user-11 :user/name "user 11" :user/friends [[:user/id :user-10] "user-12"]}
   {:db/id "user-12" :user/id :user-12 :user/name "user 12" :user/friends [[:user/id :user-11] [:user/id :user-12]]}])

(defonce db_ (r/atom (d/db conn)))
(defn ent [ref] (d/entity @db_ (d/entid @db_ ref)))
(deftest union-queries-test
  (testing "to-one union queries"
    (is (= {:todo/id :todo-1, :todo/author {:bot/name "bot 1", :bot/id :bot-1}}
          (<sub db_ [::todo {:todo/id :todo-1 sut/query-key [:todo/id :todo/author]}])))
    (is (=
          {:todo/id :todo-2, :todo/author {:user/friends (set [(ent [:user/id :user-2]) (ent [:user/id :user-3]) (ent [:user/id :user-1]) (ent [:user/id :user-5])]), :user/name "user 2", :user/id :user-2}}
          (update-in (<sub db_ [::todo {:todo/id :todo-2 sut/query-key [:todo/id :todo/author]}]) [:todo/author :user/friends] set)))
    (is (= {:todo/id :todo-2, :todo/author {:user/name "user 2"}}
          (<sub db_ [::todo {:todo/id :todo-2 sut/query-key [:todo/id {:todo/author {:user/id        [:user/name]
                                                                                     :does-not/exist [:a :b :c]}}]}])))
    (testing "support for * query"
      (is (= #:todo{:id :todo-1, :author {:bot/id :bot-1 :bot/name "bot 1"}} (<sub db_ [::todo {:todo/id :todo-1 sut/query-key [:todo/id {:todo/author ['*]}]}])))
      (is (=
            {:todo/id     :todo-2,
             :todo/author {:user/friends (set [(ent [:user/id :user-2]) (ent [:user/id :user-3]) (ent [:user/id :user-1]) (ent [:user/id :user-5])]), :user/name "user 2", :user/id :user-2}}
            (update-in (<sub db_ [::todo {:todo/id :todo-2 sut/query-key [:todo/id {:todo/author '[*]}]}]) [:todo/author :user/friends] set)))))

  (testing "to-many union queries"
    (is {:list/items   [{:comment/id           :comment-1, :comment/text "FIRST COMMENT",
                         :comment/sub-comments [{:comment/id :comment-2, :comment/text "SECOND COMMENT"}]}
                        {:todo/id     :todo-2, :todo/text "todo 2",
                         :todo/author {:user/id      :user-2, :user/name "user 2",
                                       :user/friends [{:user/id      :user-2, :user/name "user 2",
                                                       :user/friends #{(ent [:user/id :user-2]) (ent [:user/id :user-3]) (ent [:user/id :user-1]) (ent [:user/id :user-5])}}
                                                      {:user/id      :user-3, :user/name "user 3",
                                                       :user/friends [{:user/id      :user-2, :user/name "user 2",
                                                                       :user/friends #{(ent [:user/id :user-2]) (ent [:user/id :user-3]) (ent [:user/id :user-1]) (ent [:user/id :user-5])}}
                                                                      {:user/id      :user-4, :user/name "user 4",
                                                                       :user/friends [{:user/id :user-4, :user/name "user 4", :user/friends #{(ent [:user/id :user-4]) (ent [:user/id :user-3])}}
                                                                                      {:user/id :user-3, :user/name "user 3", :user/friends #{(ent [:user/id :user-2]) (ent [:user/id :user-4])}}]}]}
                                                      {:user/id      :user-1, :user/name "user 1",
                                                       :user/friends [{:user/id      :user-2, :user/name "user 2",
                                                                       :user/friends #{(ent [:user/id :user-2]) (ent [:user/id :user-3]) (ent [:user/id :user-1]) (ent [:user/id :user-5])}}]}
                                                      {:user/id      :user-5, :user/name "user 5",
                                                       :user/friends [{:user/id :user-7, :user/name "user 7"}
                                                                      {:user/id :user-6, :user/name "user 6", :user/friends [{:user/id :user-7, :user/name "user 7"}]}]}]}}],
         :list/members [{:comment/id :comment-1, :comment/text "FIRST COMMENT"} {:todo/id :todo-2, :todo/text "todo 2"}]}
      (<sub db_ [::list {:list/id :list-1 sut/query-key [{:list/items list-member-q}
                                                         {:list/members {:comment/id [:comment/id :comment/text] :todo/id [:todo/id :todo/text]}}]}]))

    (testing "unions should only return queried-for branches"
      (is (= {:list/items []} (<sub db_ [::list {:list/id :list-1 sut/query-key [{:list/items {:todo2/id [:todo/id :todo/text]}}]}])))
      (is (= {:list/items [{:comment/sub-comments #{(ent [:comment/id :comment-2])}, :comment/id :comment-1, :comment/text "FIRST COMMENT"}]} (<sub db_ [::list {:list/id :list-1 sut/query-key [{:list/items {:comment/id ['*]}}]}])))
      (is (= {:list/items [{:comment/id :comment-1, :comment/text "FIRST COMMENT"}]} (<sub db_ [::list {:list/id :list-1 sut/query-key [{:list/items {:comment/id [:comment/id :comment/text]}}]}])))
      (is (= {:list/items [{:todo/id :todo-2, :todo/text "todo 2"}]}
            (<sub db_ [::list {:list/id :list-1 sut/query-key [{:list/items {:todo/id [:todo/id :todo/text]}}]}]))))))

(deftest walking-test
  (testing "hashmap expansion"
    (let [out (<sub db_ [::user {`get-friends (fn [e]
                                                (let [friends (map (fn [f-id] (ent (:db/id f-id))) (:user/friends e))]
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
                              :user/friends [{:user/name "user 1", :user/id :user-1, :user/friends #{(ent [:user/id :user-2])}}
                                             {:user/name    "user 5", :user/id :user-5,
                                              :user/friends [{:user/name "user 7", :user/id :user-7} {:user/name "user 6", :user/id :user-6, :user/friends [{:user/name "user 7", :user/id :user-7}]}]}
                                             {:user/name    "user 2", :user/id :user-2,
                                              :user/friends #{(ent [:user/id :user-2]) (ent [:user/id :user-3]) (ent [:user/id :user-1]) (ent [:user/id :user-5])}}
                                             {:user/id :user-3, :user/name "user 3", :user/friends [(ent [:user/id :user-4]) (ent [:user/id :user-2])]}]}]} out))))

  (testing "collection expansion"
    (let [out1 (<sub db_ [::user {`get-friends (fn [e]
                                                 (let [friends (map (fn [f-id] (ent (:db/id f-id))) (:user/friends e))]
                                                   (->> friends
                                                     (filter (fn [{:user/keys [name]}] (or (= name "user 3") (= name "user 2") (= name "user 1"))))
                                                     (mapv (fn [{:user/keys [id]}] [:user/id id])))))
                                  :user/id     :user-1 sut/query-key [:user/name :user/id
                                                                      {(list :user/friends {sut/walk-fn-key `get-friends}) '...}]}])
          out2 (<sub db_ [::user {`get-friends (fn [e]
                                                 (let [friends (map (fn [f-id] (ent (:db/id f-id))) (:user/friends e))]
                                                   (->> friends
                                                     (filter (fn [{:user/keys [name]}] (or (= name "user 2") (= name "user 1"))))
                                                     (mapv (fn [{:user/keys [id]}] [:user/id id])))))
                                  :user/id     :user-1 sut/query-key [:user/name :user/id
                                                                      {(list :user/friends {sut/walk-fn-key `get-friends}) '...}]}])]

      (is (= {:user/name    "user 1", :user/id :user-1,
              :user/friends [{:user/name    "user 2", :user/id :user-2,
                              :user/friends [{:user/name "user 1", :user/id :user-1, :user/friends #{(ent [:user/id :user-2])}}
                                             {:user/name "user 2", :user/id :user-2, :user/friends #{(ent [:user/id :user-2]) (ent [:user/id :user-3]) (ent [:user/id :user-1]) (ent [:user/id :user-5])}}]}]}
            out2))

      (is (= {:user/name    "user 1", :user/id :user-1,
              :user/friends [{:user/name    "user 2", :user/id :user-2,
                              :user/friends [{:user/name    "user 3", :user/id :user-3,
                                              :user/friends [{:user/name    "user 2", :user/id :user-2,
                                                              :user/friends #{(ent [:user/id :user-2]) (ent [:user/id :user-3]) (ent [:user/id :user-1]) (ent [:user/id :user-5])}}]}
                                             {:user/name "user 1", :user/id :user-1, :user/friends #{(ent [:user/id :user-2])}}
                                             {:user/name    "user 2", :user/id :user-2,
                                              :user/friends #{(ent [:user/id :user-2]) (ent [:user/id :user-3]) (ent [:user/id :user-1]) (ent [:user/id :user-5])}}]}]}
            out1))))

  (testing "truthy/falsey expansion"
    (let [out (<sub db_ [::user {`keep-walking? (fn [e] (#{"user 1" "user 2"} (:user/name e))) :user/id :user-1
                                 sut/query-key  [:user/name :user/id {(list :user/friends {sut/walk-fn-key `keep-walking?}) '...}]}])]
      (comment (d/touch (d/entity @db_ 10)))
      (is (= {:user/name    "user 1",
              :user/id      :user-1,
              :user/friends [{:user/name    "user 2", :user/id :user-2,
                              :user/friends [{:user/name "user 3", :user/id :user-3, :user/friends #{(ent [:user/id :user-2]) (ent [:user/id :user-4])}}
                                             {:user/name "user 1", :user/id :user-1, :user/friends #{(ent [:user/id :user-2])}}
                                             {:user/name "user 5", :user/id :user-5, :user/friends #{(ent [:user/id :user-6]) (ent [:user/id :user-7])}}
                                             {:user/name "user 2", :user/id :user-2, :user/friends #{(ent [:user/id :user-2]) (ent [:user/id :user-3]) (ent [:user/id :user-1]) (ent [:user/id :user-5])}}]}]}
            out)))))

(deftest xform-test
  (testing "transform an attribute"
    (is
      {:user/name "USER 1", :user/id :user-1}
      (<sub db_ [::user {'upper-case-name (fn [e] (update e :user/name str/upper-case))
                         'uppercase       str/upper-case
                         :user/id         :user-1
                         sut/query-key    [(list :user/name {sut/xform-fn-key 'uppercase}) :user/id]}]))))

(comment
  (<sub db_ [::list {:list/id :list-1 sut/query-key [{:list/items list-member-q}
                                                     {:list/members {:comment/id [:comment/id :comment/text] :todo/id [:todo/id :todo/text]}}]}])
  (<sub db_ [::list {:list/id :list-1 sut/query-key [{:list/items list-member-q}
                                                     {:list/members {:comment/id [:comment/id :comment/text] :todo/id [:todo/id :todo/text]}}]}])
  (d/touch (d/entity db 1)) ; user-2
  (d/touch (d/entity db 2)) ; user-2
  (d/touch (d/entity db 3)) ; user-3
  (d/touch (d/entity db 11)) ; user-7
  (d/touch (d/entity db 13)) ; user-5
  (d/touch (d/entity db 13))
  (d/touch (d/entity db {:db/id 2}))
  (<sub db_ [::user {:user/id :user-1 sut/query-key [:user/name :user/id {:user/friends 0}]}])
  (<sub db_ [::user {:user/id :user-1 sut/query-key [:user/name :user/id {:user/friends 1}]}])
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

  (<sub db_ [::user {'upper-case-name (fn [e] (println "IN xform fn " e) (update e :user/name str/upper-case))
                     :user/id         :user-1
                     sut/query-key    [:user/name :user/id {(list :user/friends {sut/xform-fn-key 'upper-case-name}) 4}]}])
  (<sub db_ [::user {'upper-case-name (fn [e] (println "IN xform fn " e) (update e :user/name str/upper-case))
                     'keep-walking?   (fn [e] (println "IN KEEP walking?  " e) (#{"user 1" "user 2"} (:user/name e)))
                     :user/id         :user-1
                     sut/query-key    [:user/name :user/id {(list :user/friends {sut/xform-fn-key 'upper-case-name
                                                                                 sut/walk-fn-key  'keep-walking?}) '...}]}])
  )
