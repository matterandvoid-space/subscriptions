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

;; this lends itself well for property based tests
;; 1. add generators
;; implement the same test -> start data -> transact -> expected output
;; repeat

;; maybe you can do this with malli schemas
;; copy the reverse example from the clojure docs for example

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

(defn bytes->mb [amount] (/ amount (js/Math.pow 1000 2)))
(defn mb-str [amount] (str (bytes->mb amount) "MB"))
(defn get-mem [] (.. js/performance -memory -usedJSHeapSize))
(defn pr-mem [] (let [m (get-mem)] (println (mb-str m)) m))

(defn memory-test
  []
  (let [start-memory (get-mem)]
    start-memory ) )

;; now I want to:
;; in a loop - make a db that has a lot of data
;; {:a1 [1]
;;  :a2 [2}
;; then
;; sut/reg-sub


(defn mem-test-register []
  ;; register subs
  (let [curr-ns (namespace ::a)]
    (reduce
      (fn [acc num]
        (let [kw (keyword curr-ns (str "a" num))]
          (sut/reg-sub kw (fn [db] (get db kw)))
          (assoc acc kw num)))
      {}
      (range (js/Math.pow 10 3))))
  ;;
  ;; now subscribe to them
  )


(defn mem-test-subscribe
  "Tests the memoization cache to ensure it will evict"
  []
  (let [start-mem (get-mem)]
    ;; the default memoization size is 100, so 100 of these should be cached at a time.
    (doseq [x (range 200 ;(js/Math.pow 10 4)
                )]
      (sut/<sub db [::sub2 (assoc {} x x)])
      )
    (let [end-mem (get-mem)]
      [start-mem end-mem (mb-str (- end-mem start-mem))])))
(comment
  (reduce
    (fn [acc n] (conj acc (conj (mem-test-subscribe) (pr-mem))))
    []
    (range 20)
    )
  ; we see it is working, when garbage collection runs, the used memory decreases

  ; [[40274880 45661528 "5.386648MB" 45661528]
  ; [45661528 45166488 "-0.49504MB" 45166488]
  ; [45166488 45549864 "0.383376MB" 45549864]
  ; [45549864 47180288 "1.630424MB" 47180288]
  ; [47180288 49006772 "1.826484MB" 49006772]
  ; [49006772 45135584 "-3.871188MB" 45135584]
  ; [45135584 48579556 "3.443972MB" 48579556]
  ; [48579556 53173488 "4.593932MB" 53173488]
  ; [53173488 49934320 "-3.239168MB" 49934320]
  ; [49934320 53999056 "4.064736MB" 53999056]
  ; [53999056 56624700 "2.625644MB" 56624700]
  ; [56624700 60415896 "3.791196MB" 60415896]
  ; [60415896 55832040 "-4.583856MB" 55832040]
  ; [55832040 60820452 "4.988412MB" 60820452]
  ; [60820452 47500832 "-13.31962MB" 47500832]
  ; [47500832 43605116 "-3.895716MB" 43605116]
  ; [43605116 47368612 "3.763496MB" 47368612]
  ; [47368612 52015848 "4.647236MB" 52015848]
  ; [52015848 54946732 "2.930884MB" 54946732]
  ; [54946732 51004108 "-3.942624MB" 51004108]]
  )


;; really you only need one subscription registered to test this.

;; registering isn't the issue - calling <sub is the issue you want to test
;; so make a sub that takes in a vector of args
;; and does something with it -
;;
(comment (mem-test))

(comment
  (sut/<sub (ratom/atom {::a1 200}) [::a1])
  (println (mb-str (memory-test))))
