(ns space.matterandvoid.subscriptions.fulcro-eql-fn-vars-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]
    [com.fulcrologic.fulcro.application :as fulcro.app]
    [com.fulcrologic.fulcro.raw.components :as rc]
    [space.matterandvoid.subscriptions.fulcro :as subs :refer [<sub]]
    [space.matterandvoid.subscriptions.fulcro-eql :as sut]
    [space.matterandvoid.subscriptions.impl.reagent-ratom :as r]
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]))

(log/set-level! :trace)
#?(:cljs (enable-console-print!))
(set! *print-namespace-maps* false)

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
;(def comment-comp2 (sut/nc {:query [:comment/id :comment/text {:comment/author (sut/get-query user-comp)}] :name  ::comment :ident :comment/id}))
(def user-sub (subs/with-name (sut/create-component-subs user-comp nil) `user-sub))
;(def comment2-sub (sut/create-component-subs comment-comp2 {:comment/author user-sub}))
(def bot-sub (subs/with-name (sut/create-component-subs bot-comp nil) `bot-sub))
(def human-sub (subs/with-name (sut/create-component-subs human-comp nil) `human-sub))
(def comment-sub (subs/with-name (sut/create-component-subs comment-comp nil) `comment-sub))
;; could allow this syntax:
;(defcomponent-sub comment-sub comment-comp nil)
(def todo-sub (subs/with-name (sut/create-component-subs todo-comp {:todo/comment  comment-sub
                                                     :todo/comments comment-sub
                                                     :todo/author   {:bot/id bot-sub :user/id user-sub}}) `todo-sub))
(def list-sub (subs/with-name (sut/create-component-subs list-comp {:list/items   {:comment/id comment-sub :todo/id todo-sub}
                                                     :list/members {:comment/id comment-sub :todo/id todo-sub}})
                `list-sub))

(def todo-with-form-component
  (sut/nc
    {:query       [:todo/id :todo/text fs/form-config-join], :name ::todo-with-form, :ident :todo/id,
     :form-fields #{:todo/text}}))

(def todo-with-form-sub (subs/with-name (sut/create-component-subs todo-with-form-component {}) `todo-with-form-sub))

(comment
  (sut/eql-query-keys-by-type (sut/get-query todo-comp) {:todo/comment  comment-sub
                                                         :todo/comments comment-sub
                                                         :todo/author   {:bot/id bot-sub :user/id user-sub}})
  (def todo-sub (subs/with-name (sut/create-component-subs todo-comp nil) `todo-sub)))

;(def list-sub (sut/create-component-subs list-comp nil))
;(run! sut/register-component-subs! [user-comp bot-comp comment-comp todo-comp list-comp human-comp])

(def db_ (r/atom {:comment/id {:comment-1 {:comment/id           :comment-1 :comment/text "FIRST COMMENT"
                                           :comment/author       [:user/id :user-1]
                                           :comment/sub-comments [[:comment/id :comment-2]]}
                               :comment-2 {:comment/id :comment-2 :comment/text "SECOND COMMENT"}
                               :comment-3 {:comment/id :comment-3 :comment/text "THIRD COMMENT"}}
                  :list/id    {:list-1 {:list/id      :list-1 :list/name "first list"
                                        :list/members [[:comment/id :comment-1] [:todo/id :todo-2]]
                                        :list/items   [[:todo/id :todo-2] [:comment/id :comment-1]]}}
                  ;; to-one cycles
                  :human/id   {:human-1 {:human/id :human-1 :human/name "human Y" :human/best-friend [:human/id :human-1]}
                               :human-2 {:human/id :human-2 :human/name "human X" :human/best-friend [:human/id :human-3]}
                               :human-3 {:human/id :human-3 :human/name "human Z" :human/best-friend [:human/id :human-1]}}

                  ;; to-many cycle
                  :user/id    {:user-1 {:user/id :user-1 :user/name "user 1" :user/friends [[:user/id :user-2]]}
                               :user-2 {:user/id :user-2 :user/name "user 2" :user/friends [[:user/id :user-2] [:user/id :user-1] [:user/id :user-3]]}
                               :user-3 {:user/id :user-3 :user/name "user 3" :user/friends [[:user/id :user-2] [:user/id :user-4]]}
                               :user-4 {:user/id :user-4 :user/name "user 4" :user/friends [[:user/id :user-3] [:user/id :user-4]]}}
                  :bot/id     {:bot-1 {:bot/id :bot-1 :bot/name "bot 1"}}
                  :todo/id    {:todo-1 {:todo/id      :todo-1 :todo/text "todo 1"
                                        :todo/author  [:bot/id :bot-1]
                                        :todo/comment [:comment/id :comment-1]}
                               :todo-2 {:todo/id :todo-2 :todo/text "todo 2" :todo/author [:user/id :user-2]}
                               :todo-3 {:todo/id :todo-3 :todo/text "todo 3" :todo/comments [[:comment/id :comment-1] [:comment/id :comment-3]]}}}))

(def app (assoc (fulcro.app/fulcro-app {}) ::fulcro.app/state-atom db_))

(let [todo-w-form-ident [:todo/id :todo-with-form]
      todo-w-form       {:todo/id :todo-with-form :todo/text "todo with-form"}]
  (swap! (::fulcro.app/state-atom app)
    (fn [state]
      (-> state
        (assoc-in todo-w-form-ident todo-w-form)
        (fs/add-form-config* todo-with-form-component todo-w-form-ident)))))

(comment
  ;; wow. it works....
  (bot-sub app {:bot/id :bot-1})
  (<sub app [user-sub {:user/id :user-1 sut/query-key [:user/name]}])
  (<sub app [user-sub {:user/id :user-1 sut/query-key [:user/name :user/id {:user/friends 1}]}])
  (<sub app [user-sub {:user/id :user-1 sut/query-key [:user/name :user/id {:user/friends 0}]}])
  (<sub app [user-sub {:user/id :user-1 sut/query-key [:user/name :user/id {:user/friends '...}]}])
  )

;(<sub app [user-sub {:user/id 1 sut/query-key [:user/id {:user/friends `keep-walking?}]}])

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

(comment
  (fulcro.app/current-state app)
  (<sub app [todo-sub {:todo/id :todo-1 sut/query-key [:todo/id :todo/author]}])
  (<sub app [list-sub {:list/id :list-1 sut/query-key [{:list/items list-member-q}
                                                       {:list/members {:comment/id [:comment/id :comment/text] :todo/id [:todo/id :todo/text]}}]}])
  )
(deftest union-queries-test
  (testing "to-one union queries"
    (is (= {:todo/id :todo-1, :todo/author {:bot/name "bot 1", :bot/id :bot-1}}
          (<sub app [todo-sub {:todo/id :todo-1 sut/query-key [:todo/id :todo/author]}])))
    (is (=
          {:todo/id :todo-2, :todo/author #:user{:id :user-2, :name "user 2", :friends [[:user/id :user-2] [:user/id :user-1] [:user/id :user-3]]}}
          (<sub app [todo-sub {:todo/id :todo-2 sut/query-key [:todo/id :todo/author]}])))
    (is (= {:todo/id :todo-2, :todo/author {:user/name "user 2"}}
          (<sub app [todo-sub {:todo/id :todo-2 sut/query-key [:todo/id {:todo/author {:user/id        [:user/name]
                                                                                       :does-not/exist [:a :b :c]}}]}])))
    (testing "support for * query"
      (is (= #:todo{:id :todo-1, :author #:bot{:id :bot-1, :name "bot 1"}} (<sub app [todo-sub {:todo/id :todo-1 sut/query-key [:todo/id {:todo/author ['*]}]}])))
      (is (= #:todo{:id :todo-2, :author #:user{:id :user-2, :name "user 2", :friends [[:user/id :user-2] [:user/id :user-1] [:user/id :user-3]]}}
            (<sub app [todo-sub {:todo/id :todo-2 sut/query-key [:todo/id {:todo/author '[*]}]}])))))

  (testing "to-many union queries"
    (is (=
          #:list{:items   [#:todo{:id     :todo-2, :text "todo 2",
                                  :author #:user{:id      :user-2, :name "user 2",
                                                 :friends [#:user{:id :user-2, :name "user 2", :friends [[:user/id :user-2] [:user/id :user-1] [:user/id :user-3]]}
                                                           #:user{:id :user-1, :name "user 1", :friends [#:user{:id :user-2, :name "user 2", :friends [[:user/id :user-2] [:user/id :user-1] [:user/id :user-3]]}]}
                                                           #:user{:id :user-3, :name "user 3", :friends [#:user{:id :user-2, :name "user 2", :friends [[:user/id :user-2] [:user/id :user-1] [:user/id :user-3]]}
                                                                                                         #:user{:id :user-4, :name "user 4", :friends [#:user{:id :user-3, :name "user 3", :friends [[:user/id :user-2] [:user/id :user-4]]}
                                                                                                                                                       #:user{:id :user-4, :name "user 4", :friends [[:user/id :user-3] [:user/id :user-4]]}]}]}]}}
                           #:comment{:id :comment-1, :text "FIRST COMMENT", :sub-comments [#:comment{:id :comment-2, :text "SECOND COMMENT"}]}],
                 :members [#:comment{:id :comment-1, :text "FIRST COMMENT"} #:todo{:id :todo-2, :text "todo 2"}]}
          (<sub app [list-sub {:list/id :list-1 sut/query-key [{:list/items list-member-q}
                                                               {:list/members {:comment/id [:comment/id :comment/text] :todo/id [:todo/id :todo/text]}}]}])))

    (testing "unions should only return queried-for branches"
      (is (= {:list/items []} (<sub app [list-sub {:list/id :list-1 sut/query-key [{:list/items {:todo2/id [:todo/id :todo/text]}}]}])))
      (is (= {:list/items [{:todo/id :todo-2, :todo/text "todo 2"}]}
            (<sub app [list-sub {:list/id :list-1 sut/query-key [{:list/items {:todo/id [:todo/id :todo/text]}}]}]))))))

(deftest plain-join-queries

  (testing "to-one joins"
    (is (= #:todo{:id :todo-1, :author #:bot{:id :bot-1, :name "bot 1"}, :comment #:comment{:id :comment-1, :text "FIRST COMMENT", :sub-comments [[:comment/id :comment-2]]}}
          (<sub app [todo-sub {:todo/id :todo-1 sut/query-key [:todo/id :todo/author :todo/comment]}])))
    (is (= #:todo{:id :todo-1,, :comment #:comment{:id :comment-1, :text "FIRST COMMENT"}}
          (<sub app [todo-sub {:todo/id :todo-1 sut/query-key [:todo/id {:todo/comment [:comment/id :comment/text]}]}]))))

  (testing "to-many joins"
    (is (= {:todo/id :todo-2} (<sub app [todo-sub {:todo/id :todo-2 sut/query-key [:todo/id {:todo/comments [:comment/id :comment/text]}]}])))
    (is (= #:todo{:id :todo-3, :comments [#:comment{:id :comment-1, :text "FIRST COMMENT"} #:comment{:id :comment-3, :text "THIRD COMMENT"}]}
          (<sub app [todo-sub {:todo/id :todo-3 sut/query-key [:todo/id {:todo/comments [:comment/id :comment/text]}]}])))
    (testing "support for '[*]"
      (is (=
            {:todo/id       :todo-3,
             :todo/comments [{:comment/sub-comments [[:comment/id :comment-2]], :comment/id :comment-1, :comment/text "FIRST COMMENT" :comment/author [:user/id :user-1]}
                             {:comment/sub-comments sut/missing-val,
                              :comment/id           :comment-3,
                              :comment/text         "THIRD COMMENT"}]}
            (<sub app [todo-sub {:todo/id :todo-3 sut/query-key [:todo/id {:todo/comments ['*]}]}]))))))

(deftest recursive-join-queries
  (is (= #:user{:name "user 1", :id :user-1, :friends [[:user/id :user-2]]}
        (<sub app [user-sub {:user/id :user-1 sut/query-key [:user/name :user/id {:user/friends 0}]}])))

  (is (= #:user{:name    "user 1", :id :user-1,
                :friends [#:user{:name "user 2", :id :user-2, :friends [[:user/id :user-2] [:user/id :user-1] [:user/id :user-3]]}]}
        (<sub app [user-sub {:user/id :user-1 sut/query-key [:user/name :user/id {:user/friends 1}]}])))

  (testing "handles self-cycle"
    (is (= {:human/id :human-1, :human/best-friend [:human/id :human-1], :human/name "human Y"}
          (<sub app [human-sub {:human/id :human-1 sut/query-key [:human/id :human/best-friend :human/name]}])))

    (is (= {:human/id :human-1, :human/best-friend [:human/id :human-1], :human/name "human Y"}
          (<sub app [human-sub {:human/id :human-1 sut/query-key [:human/id {:human/best-friend '...} :human/name]}])))

    (testing "handles multi-level to-one cycle"
      (is (= {:human/id          :human-2,
              :human/best-friend {:human/id          :human-3,
                                  :human/best-friend {:human/id :human-1, :human/best-friend [:human/id :human-1] :human/name "human Y"},
                                  :human/name        "human Z"},
              :human/name        "human X"}
            (<sub app [human-sub {:human/id :human-2 sut/query-key [:human/id {:human/best-friend '...} :human/name]}]))))

    (testing "handles finite self-recursive (to-one) cycles"
      (is (= #:human{:id :human-1, :best-friend #:human{:id :human-1, :best-friend [:human/id :human-1], :name "human Y"}, :name "human Y"}
            (<sub app [human-sub {:human/id :human-1 sut/query-key [:human/id {:human/best-friend 2} :human/name]}])))))

  (testing "handles to-many recursive cycles"
    (is (=
          #:user{:name    "user 1", :id :user-1,
                 :friends [#:user{:name    "user 2", :id :user-2,
                                  :friends [#:user{:name "user 2", :id :user-2, :friends [[:user/id :user-2] [:user/id :user-1] [:user/id :user-3]]}
                                            #:user{:name "user 1", :id :user-1, :friends [[:user/id :user-2]]}
                                            #:user{:name    "user 3", :id :user-3,
                                                   :friends [#:user{:name "user 2", :id :user-2, :friends [[:user/id :user-2] [:user/id :user-1] [:user/id :user-3]]}
                                                             #:user{:name    "user 4", :id :user-4,
                                                                    :friends [#:user{:name "user 3", :id :user-3, :friends [[:user/id :user-2] [:user/id :user-4]]}
                                                                              #:user{:name "user 4", :id :user-4, :friends [[:user/id :user-3] [:user/id :user-4]]}]}]}]}]}
          (<sub app [user-sub {:user/id :user-1 sut/query-key [:user/name :user/id {:user/friends '...}]}])))))

(deftest queries-test
  (testing "props"
    (let [out1 {:todo/id :todo-1 :todo/text "todo 1"}
          out2 #:todo{:comment  [:comment/id :comment-1],
                      :comments sut/missing-val
                      :author   [:bot/id :bot-1],
                      :id       :todo-1,
                      :text     "todo 1"}]
      (is (= out1 (<sub app [todo-sub {:todo/id :todo-1 sut/query-key [:todo/id :todo/text]}])))
      (is (= out1 (todo-sub app {:todo/id :todo-1 sut/query-key [:todo/id :todo/text]})))
      (is (= out2 (<sub app [todo-sub {:todo/id :todo-1 sut/query-key ['* :todo/text]}])))
      (is (= out2 (todo-sub app {:todo/id :todo-1 sut/query-key ['* :todo/text]})))))

  (testing "entity subscription with no query returns all attributes"
    (is (=
          {:list/items   [{:todo/comments sut/missing-val
                           :todo/author   {:user/friends [[:user/id :user-2] [:user/id :user-1] [:user/id :user-3]], :user/name "user 2", :user/id :user-2},
                           :todo/comment  sut/missing-val
                           :todo/text     "todo 2",
                           :todo/id       :todo-2}
                          {:comment/sub-comments [[:comment/id :comment-2]], :comment/id :comment-1, :comment/text "FIRST COMMENT"}],
           :list/members [{:comment/sub-comments [[:comment/id :comment-2]], :comment/id :comment-1, :comment/text "FIRST COMMENT"}
                          {:todo/comments sut/missing-val
                           :todo/author   {:user/friends [[:user/id :user-2] [:user/id :user-1] [:user/id :user-3]], :user/name "user 2", :user/id :user-2},
                           :todo/comment  sut/missing-val
                           :todo/text     "todo 2",
                           :todo/id       :todo-2}],
           :list/name    "first list",
           :list/id      :list-1}
          (<sub app [list-sub {:list/id :list-1}])))))

(deftest form-state-test
  (is (=
        {:todo/id    :todo-with-form,
         :todo/text  "todo with-form",
         ::fs/config {::fs/id         [:todo/id :todo-with-form], ::fs/fields #{:todo/text}, ::fs/complete? nil, ::fs/subforms {},
                      ::fs/pristine-state {:todo/text "todo with-form"}}}
        (todo-with-form-sub app {:todo/id      :todo-with-form
                                 sut/query-key (rc/get-query todo-with-form-component)}))))

;#?(:cljs
;   (deftest perf-test
;     (testing "timing of db->tree"
;       (simple-benchmark []
;         (<sub app [list-sub {:list/id :list-1}])
;         100
;         ))))


(comment

  (set! *print-namespace-maps* false)


  (fdn/db->tree (rc/get-query list-comp) [:list/id :list-1] (fulcro.app/current-state app))
  #:list{:id :list-1,
         :name "first list",
         :items [#:todo{:id :todo-2,
                        :text "todo 2",
                        :author #:user{:id :user-2,
                                       :name "user 2",
                                       :friends [#:user{:id :user-2, :name "user 2", :friends []}
                                                 #:user{:id :user-1, :name "user 1", :friends []}
                                                 #:user{:id :user-3,
                                                        :name "user 3",
                                                        :friends [#:user{:id :user-4, :name "user 4", :friends []}]}]}}
                 #:comment{:id :comment-1,
                           :text "FIRST COMMENT",
                           :sub-comments [#:comment{:id :comment-2, :text "SECOND COMMENT"}]}],
         :members [#:comment{:id :comment-1,
                             :text "FIRST COMMENT",
                             :sub-comments [#:comment{:id :comment-2, :text "SECOND COMMENT"}]}
                   #:todo{:id :todo-2,
                          :text "todo 2",
                          :author #:user{:id :user-2,
                                         :name "user 2",
                                         :friends [#:user{:id :user-2, :name "user 2", :friends []}
                                                   #:user{:id :user-1, :name "user 1", :friends []}
                                                   #:user{:id :user-3,
                                                          :name "user 3",
                                                          :friends [#:user{:id :user-4, :name "user 4", :friends []}]}]}}]}

  ;; sub

  (=
    (<sub app [list-sub {:list/id :list-1 sut/query-key (rc/get-query list-comp)}])
    (fdn/db->tree (rc/get-query list-comp) [:list/id :list-1] (fulcro.app/current-state app)))
  (log/set-level! :error)
  (<sub app [todo-sub {:todo/id :todo-1 sut/query-key [:todo/id :todo/text]}])

  (<sub (fulcro.app/current-state app) [todo-sub {:todo/id :todo-1 sut/query-key [:todo/id :todo/text]}])
  (todo-sub (fulcro.app/current-state app) {:todo/id :todo-1 sut/query-key [:todo/id :todo/text]})

  (simple-benchmark [args {:todo/id :todo-1 sut/query-key [:todo/id :todo/text]}
                     sub-args [todo-sub args]]
    (<sub app sub-args) 1000)

  (simple-benchmark [args {:todo/id :todo-1 sut/query-key [:todo/id :todo/text]} s (fulcro.app/current-state app)]
    (todo-sub s args) 10000)

  ;; full todo query
  (simple-benchmark [args {:todo/id :todo-1 sut/query-key (rc/get-query todo-comp)} s (fulcro.app/current-state app)]
    (todo-sub s args) 10000)

  (simple-benchmark [q (rc/get-query todo-comp)
                     ident [:todo/id :todo-1]
                     state (fulcro.app/current-state app)]
    (fdn/db->tree q ident state)
    10000)

  (todo-sub app {:todo/id :todo-1 sut/query-key ['* :todo/text]})

  (simple-benchmark [q [:todo/id :todo/text]
                     ident [:todo/id :todo-1]
                     state (fulcro.app/current-state app)]
    (fdn/db->tree q ident state)
     10000)

  (simple-benchmark [q (rc/get-query list-comp)
                     ident [:list/id :list-1]
                     state (fulcro.app/current-state app)]
    (fdn/db->tree  q ident state)
    1000)

  (simple-benchmark [q (rc/get-query list-comp)
                     ident [:list/id :list-1]
                     state (fulcro.app/current-state app)]
    (fdn/db->tree  q ident state)
    1000)
  (<sub app [list-sub {:list/id :list-1 sut/query-key [{:list/items list-member-q}
                                                       {:list/members {:comment/id [:comment/id :comment/text] :todo/id [:todo/id :todo/text]}}]}])
  (<sub app [todo-sub {:todo/id :todo-1 sut/query-key [:todo/id :todo/author]}])
  (<sub app [list-sub {:list/id :list-1}])
  (<sub app [list-sub {:list/id :list-1 sut/query-key [#_:list/name {:list/members {:comment/id [:comment/id :comment/text]
                                                                                    :todo/id    [:todo/id :todo/text]}}]}])
  (<sub app [list-sub {:list/id :list-1 sut/query-key [:list/name {:list/members {:comment/id [:comment/id :comment/text
                                                                                               {:comment/sub-comments '...}]
                                                                                  :todo/id    [:todo/id :todo/text]}}]}])
  (<sub app [list-sub {:list/id :list-1 sut/query-key [:list/name
                                                       {:list/members {:comment/id [:comment/id :comment/text {:comment/sub-comments '...}] :todo/id [:todo/id :todo/text]}}
                                                       {:list/items

                                                        {:comment/id [:comment/id :comment/text {:comment/sub-comments 0}] :todo/id [:todo/id :todo/text]}
                                                        }
                                                       ]}])
  (<sub app [list-sub {:list/id :list-1 sut/query-key [; :list/name
                                                       ;{:list/members (sut/get-query list-member-comp)}
                                                       {:list/items {
                                                                     ;:comment/id [:comment/id :comment/text {:comment/sub-comments 0}]
                                                                     :todo/id [:todo/id :todo/text]}}
                                                       ]}])

  )
