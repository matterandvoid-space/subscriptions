(ns space.matterandvoid.subscriptions.core-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [reagent.ratom :as ratom]
    [datascript.core :as d]
    [taoensso.timbre :as log]
    [space.matterandvoid.subscriptions.core :as sut]))

(sut/reg-sub :hello
  (fn [db] (:hello db)))

(sut/defsub sub2 :-> :sub2)

(def schema {:todo/tags {:db/cardinality :db.cardinality/many}
             ;:todo/id   {:db/valueType :db.type/ref}
             ;:todo/text {:db/valueType :db.type/string}
             :todo/id   {:db/unique :db.unique/identity}
             :todo/done {:db/index true}
             :todo/due  {:db/index true}})

(defonce conn (d/create-conn schema))
(defonce dscript-db_ (ratom/atom (d/db conn)))
(comment @dscript-db_)

(defn make-todo [id text] {:todo/id id :todo/text text})

(def todo1 (make-todo #uuid"6848eac7-245c-4c5c-b932-8525279d4f0a" "hello1"))
(def todo2 (make-todo #uuid"b13319dd-3200-40ec-b8ba-559e404f9aa5" "hello2"))
(def todo3 (make-todo #uuid"0ecf7b8a-c3ab-42f7-a1e3-118fcbcee30c" "hello3"))
(def todo4 (make-todo #uuid"5860a879-a5e6-4a5f-844b-c8ecc6443f2e" "hello4"))

(defn transact! [conn data]
  (d/transact! conn data)
  (reset! dscript-db_ (d/db conn)))

(use-fixtures :each
  {:before (fn []
             (transact! conn [todo1 todo2 todo3 todo4]))})

(defonce db (ratom/atom {:sub2 500 :hello "hello"}))

(deftest basic-test
  (is (= 500 (sub2 db)))
  (is (= 500 (sub2 db {})))
  (is (thrown-with-msg? js/Error #"Args to the query vector must be one map" (= 500 (sub2 db 13))))
  (is (= 500 (sut/<sub db [::sub2])))
  (is (= 500 @(sut/subscribe db [::sub2])))
  (is (= "hello" (sut/<sub db [:hello])))
  (is (= "hello" @(sut/subscribe db [:hello]))))

(deftest invalid-start-signal
  (is (thrown-with-msg? js/Error #"Your input signal must be a reagent.ratom" (sub2 (atom {})))))

(sut/defsub all-todos
  :-> (fn [db]
        (log/info "compute all todos")
        (d/q '[:find [(pull ?e [*]) ...] :where [?e :todo/id]] db)))

;(sut/defsub last-tx
;  (fn [conn] (d/q '[:find [(pull ?e [*]) ...] :where [?e :todo/id]] (d/db conn)))
;  )
(sut/defsub sorted-todos :<- [::all-todos] :-> (partial sort-by :todo/text))
(sut/defsub rev-sorted-todos :<- [::sorted-todos] :-> reverse)
(sut/defsub sum-lists :<- [::all-todos] :<- [::rev-sorted-todos] :-> (partial mapv count))

(comment
  (d/q '[:find [(pull ?e [*]) ...] :where [?e :todo/id]] (d/db conn))
  ;(d/transact! @dscript-db_ [todo1 todo2 todo3 todo4])
  (transact! conn [(make-todo (random-uuid) "hello4-5")])

  (all-todos dscript-db_)
  (sorted-todos dscript-db_)
  (rev-sorted-todos dscript-db_)
  (sum-lists dscript-db_)
  )

(deftest datascript-test
  (is (= (all-todos dscript-db_)
        [{:db/id 1, :todo/id #uuid"6848eac7-245c-4c5c-b932-8525279d4f0a", :todo/text "hello1"}
         {:db/id 2, :todo/id #uuid"b13319dd-3200-40ec-b8ba-559e404f9aa5", :todo/text "hello2"}
         {:db/id 3, :todo/id #uuid"0ecf7b8a-c3ab-42f7-a1e3-118fcbcee30c", :todo/text "hello3"}
         {:db/id 4, :todo/id #uuid"5860a879-a5e6-4a5f-844b-c8ecc6443f2e", :todo/text "hello4"}]))
  (is (= (sorted-todos dscript-db_)
        [{:db/id 1, :todo/id #uuid"6848eac7-245c-4c5c-b932-8525279d4f0a", :todo/text "hello1"}
         {:db/id 2, :todo/id #uuid"b13319dd-3200-40ec-b8ba-559e404f9aa5", :todo/text "hello2"}
         {:db/id 3, :todo/id #uuid"0ecf7b8a-c3ab-42f7-a1e3-118fcbcee30c", :todo/text "hello3"}
         {:db/id 4, :todo/id #uuid"5860a879-a5e6-4a5f-844b-c8ecc6443f2e", :todo/text "hello4"}]))
  (is (= (rev-sorted-todos dscript-db_)
        [{:db/id 4, :todo/id #uuid"5860a879-a5e6-4a5f-844b-c8ecc6443f2e", :todo/text "hello4"}
         {:db/id 3, :todo/id #uuid"0ecf7b8a-c3ab-42f7-a1e3-118fcbcee30c", :todo/text "hello3"}
         {:db/id 2, :todo/id #uuid"b13319dd-3200-40ec-b8ba-559e404f9aa5", :todo/text "hello2"}
         {:db/id 1, :todo/id #uuid"6848eac7-245c-4c5c-b932-8525279d4f0a", :todo/text "hello1"}]))
  (is (= (sum-lists dscript-db_) [4 4]))

  (transact! conn [(make-todo #uuid"4d245cb4-a6a4-4c28-b605-5b6d451ee35f" "hello5")])

  (is (= (all-todos dscript-db_)
        [{:db/id 1, :todo/id #uuid"6848eac7-245c-4c5c-b932-8525279d4f0a", :todo/text "hello1"}
         {:db/id 2, :todo/id #uuid"b13319dd-3200-40ec-b8ba-559e404f9aa5", :todo/text "hello2"}
         {:db/id 3, :todo/id #uuid"0ecf7b8a-c3ab-42f7-a1e3-118fcbcee30c", :todo/text "hello3"}
         {:db/id 4, :todo/id #uuid"5860a879-a5e6-4a5f-844b-c8ecc6443f2e", :todo/text "hello4"}
         {:db/id 5, :todo/id #uuid"4d245cb4-a6a4-4c28-b605-5b6d451ee35f", :todo/text "hello5"}]))
  (is (= (sorted-todos dscript-db_)
        [{:db/id 1, :todo/id #uuid"6848eac7-245c-4c5c-b932-8525279d4f0a", :todo/text "hello1"}
         {:db/id 2, :todo/id #uuid"b13319dd-3200-40ec-b8ba-559e404f9aa5", :todo/text "hello2"}
         {:db/id 3, :todo/id #uuid"0ecf7b8a-c3ab-42f7-a1e3-118fcbcee30c", :todo/text "hello3"}
         {:db/id 4, :todo/id #uuid"5860a879-a5e6-4a5f-844b-c8ecc6443f2e", :todo/text "hello4"}
         {:db/id 5, :todo/id #uuid"4d245cb4-a6a4-4c28-b605-5b6d451ee35f", :todo/text "hello5"}]))
  (is (= (rev-sorted-todos dscript-db_)
        [{:db/id 5, :todo/id #uuid"4d245cb4-a6a4-4c28-b605-5b6d451ee35f", :todo/text "hello5"}
         {:db/id 4, :todo/id #uuid"5860a879-a5e6-4a5f-844b-c8ecc6443f2e", :todo/text "hello4"}
         {:db/id 3, :todo/id #uuid"0ecf7b8a-c3ab-42f7-a1e3-118fcbcee30c", :todo/text "hello3"}
         {:db/id 2, :todo/id #uuid"b13319dd-3200-40ec-b8ba-559e404f9aa5", :todo/text "hello2"}
         {:db/id 1, :todo/id #uuid"6848eac7-245c-4c5c-b932-8525279d4f0a", :todo/text "hello1"}]))
  (is (= (sum-lists dscript-db_) [5 5])))

(comment
  (swap! db update :sub2 inc)
  (swap! db (fn [d] (assoc d :sub2 500)))
  (swap! db assoc :sub2 500)
  (sub2 db)
  @(sut/subscribe db [:hello])
  )
;(repeatedly 10 random-uuid)
(comment

  (d/q '[:find ?id :where [?e :todo/id ?id]] (d/db @dscript-db))
  (d/q '[:find (pull ?e [*]) :where [?e :todo/id ?id]] (d/db @dscript-db))
  (d/q '{:find  [?id]
         :where [[?e :todo/id ?id]]}
    (d/db conn))
  )
