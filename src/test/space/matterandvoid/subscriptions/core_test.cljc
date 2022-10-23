(ns space.matterandvoid.subscriptions.core-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [space.matterandvoid.subscriptions.reagent-ratom :as ratom]
    [datascript.core :as d]
    [taoensso.timbre :as log]
    [space.matterandvoid.subscriptions.core :as sut]))

(log/set-level! :debug)
(sut/reg-sub :hello (fn [db] (:hello db)))

(def layer-2-sub-w-args
  (sut/with-name
    (fn [db {:keys [path]}] (ratom/cursor db path)) `layer-2-sub-w-args))
(sut/defregsub sub2 :-> :sub2)
(sut/defsub sub2' :-> :sub2)
(sut/defsub layer3-def1 :<- [sub2'] (fn [num] (* 10 num)))
(sut/defsub layer3-def2 :<- [sub2'] :-> #(* 10 %))

(sut/reg-layer2-sub ::sub-2-accessor [:sub2])
(sut/reg-layer2-sub ::sub-2-accessor-5 (fn [_db _args] [:sub2]))
(sut/deflayer2-sub sub-2-accessor-2 [:sub2])
(sut/deflayer2-sub sub-2-accessor-3 :sub2)
(sut/deflayer2-sub sub-2-accessor-4 (fn [_db args] [(:kw args)]))
(sut/defsub layer3-def3 :<- [layer3-def2] :<- [layer3-def1] :-> #(apply + %))
(sut/defsub layer3-def4 :<- [layer3-def2] :<- [layer3-def1] :-> (fn [a] (apply + a)))
(sut/defsub layer3-def5 :<- [layer3-def2] :<- [layer3-def1] (fn [a] (apply + a)))
(sut/defsub layer3-def6
  (fn [db args]
    {:val1 (layer3-def1 db)
     :val2 (layer3-def2 db)})
  (fn [{:keys [val1 val2]}]
    (+ val1 val2)))

(sut/defsub layer2-fn (fn [db] (* -1 (:sub2 db))))
(sut/defsub layer2-fn2 :-> (comp (partial * -1) :sub2))
(sut/defsub layer2-fn3 :sub3)
(sut/defsub layer2-fn4 :-> :sub3)
(sut/deflayer2-sub layer2-fn5 :sub3)

(comment
  (macroexpand '(sut/defsub layer2-fn4 :-> :sub3)))
;; also need to test using arguments from the hashmap, that they flow through.

(def schema {:todo/id {:db/unique :db.unique/identity}})
(defonce conn_ (atom (d/create-conn schema)))
(defonce dscript-db_ (ratom/atom (d/db @conn_)))

(defn make-todo [id text] {:todo/id id :todo/text text})

(def todo1 (make-todo #uuid"6848eac7-245c-4c5c-b932-8525279d4f0a" "hello1"))
(def todo2 (make-todo #uuid"b13319dd-3200-40ec-b8ba-559e404f9aa5" "hello2"))
(def todo3 (make-todo #uuid"0ecf7b8a-c3ab-42f7-a1e3-118fcbcee30c" "hello3"))
(def todo4 (make-todo #uuid"5860a879-a5e6-4a5f-844b-c8ecc6443f2e" "hello4"))

(defn transact! [conn data]
  (d/transact! conn data)
  (reset! dscript-db_ (d/db conn)))

(use-fixtures :each
  #?(:clj  (fn [f]
             (reset! conn_ (d/create-conn schema))
             (reset! dscript-db_ (ratom/atom (d/db @conn_)))
             (transact! @conn_ [todo1 todo2 todo3 todo4])
             (f))
     :cljs {:before (fn [] (transact! @conn_ [todo1 todo2 todo3 todo4]))}))

(defonce db (ratom/atom {:sub2 500 :hello "hello"}))

(def layer-2-sub2 (sut/with-name (fn [& args] (ratom/cursor db [:sub2])) `layer-2-sub2))

(def layer-2-sub3 (sut/with-name (let [sub-fn
                         (fn [& args] (ratom/cursor db [:sub2]))]
                     (with-meta (fn [& args] @(sub-fn)) {:subscription sub-fn}))
                    `layer-2-sub3))

(def layer-2-sub4 (sut/with-name (sut/sub-fn (fn [& args] (ratom/cursor db [:sub2]))) `layer-2-sub4))

(deftest basic-test
  (is (= 500 (sut/<sub db [::sub-2-accessor])))
  (is (= 500 (sut/<sub db [::sub-2-accessor-5])))
  (is (= 500 (sut/<sub db [sub-2-accessor-2])))
  (is (= 500 (sut/<sub db [sub-2-accessor-2 {}])))
  (is (= 500 (sut/<sub db [sub-2-accessor-3])))
  (is (= 500 (sut/<sub db [sub-2-accessor-3 {}])))
  (is (= 500 (sut/<sub db [sub-2-accessor-4 {:kw :sub2}])))
  (is (= 500 (sub-2-accessor-4 db {:kw :sub2})))
  (is (= 500 (sub-2-accessor-2 db)))
  (is (= 500 (sub-2-accessor-2 db nil)))
  (is (= 500 (sub-2-accessor-3 db)))
  (is (= 500 (sub-2-accessor-3 db nil)))
  (is (= 500 (sut/<sub db [layer-2-sub2])))
  (is (= -500 (sut/<sub db [layer2-fn])))
  (is (= -500 (sut/<sub db [layer2-fn2])))
  (is (= -500 (sut/<sub db [layer2-fn2 {:abcd 5}])))
  (is (= {:abcd 5} (sut/<sub db [layer2-fn3 {:abcd 5}])))
  (is (= {:abcd 5} (sut/<sub db [layer2-fn3 {:abcd 5}])))
  (is (= nil (sut/<sub db [layer2-fn3])))
  (is (= nil (sut/<sub db [layer2-fn4])))
  (is (= nil (sut/<sub db [layer2-fn4 {:abcd 5}])))
  (is (= nil (sut/<sub db [layer2-fn5])))
  (is (= nil (sut/<sub db [layer2-fn5 {:abcd 5}])))
  (is (= 500 @(layer-2-sub2)))
  (is (= 500 (layer-2-sub3)))
  (is (= 500 (layer-2-sub4)))
  (is (= 500 @(layer-2-sub-w-args db {:path [:sub2]})))
  (is (= 500 (sut/<sub db [layer-2-sub-w-args {:path [:sub2]}])))
  (is (= 500 (sub2 db)))
  (is (= 500 (sub2' db)))
  (is (= 500 (sub2' db {})))
  (is (= 5000 (layer3-def1 db {})))
  (is (= 5000 (layer3-def1 db)))
  (is (= 5000 (layer3-def2 db {})))
  (is (= 5000 (layer3-def2 db)))
  (is (= 5000 (sut/<sub db [layer3-def1])))
  (is (= 5000 (sut/<sub db [layer3-def1 {}])))
  (is (= 5000 (sut/<sub db [layer3-def2])))
  (is (= 5000 (sut/<sub db [layer3-def2 {}])))
  (is (= 10000 (sut/<sub db [layer3-def3 {}])))
  (is (= 10000 (layer3-def3 db {})))
  (is (= 10000 (layer3-def3 db)))
  (is (= 10000 (sut/<sub db [layer3-def4])))
  (is (= 10000 (sut/<sub db [layer3-def4 {}])))
  (is (= 10000 (sut/<sub db [layer3-def5])))
  (is (= 10000 (sut/<sub db [layer3-def5 {}])))


  (is (= 500 (sub2 db {})))
  (is (thrown-with-msg? #?(:cljs js/Error :clj Exception) #"Args to the query vector must be one map" (= 500 (sub2 db 13))))
  (is (= 500 (sut/<sub db [::sub2])))
  (is (= 500 @(sut/subscribe db [::sub2])))
  (is (= "hello" (sut/<sub db [:hello])))
  (is (= "hello" @(sut/subscribe db [:hello]))))

#?(:cljs
   (deftest invalid-start-signal
     (is (thrown-with-msg? js/Error #"Your input signal must be a reagent.ratom" (sub2 (atom {}))))))

(sut/defregsub all-todos
  :-> (fn [db]
        (log/info "compute all todos")
        (d/q '[:find [(pull ?e [*]) ...] :where [?e :todo/id]] db)))

(sut/defregsub sorted-todos :<- [::all-todos] :-> (partial sort-by :todo/text))
(sut/defregsub rev-sorted-todos :<- [::sorted-todos] :-> reverse)
(sut/defregsub sum-lists :<- [::all-todos] :<- [::rev-sorted-todos] :-> (partial mapv count))

(comment
  (d/q '[:find [(pull ?e [*]) ...] :where [?e :todo/id]] (d/db @conn_))
  ;(d/transact! @dscript-db_ [todo1 todo2 todo3 todo4])
  (transact! @conn_ [(make-todo (random-uuid) "hello4-5")])

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
  (is (= (set (all-todos dscript-db_))
        (set [{:db/id 1, :todo/id #uuid"6848eac7-245c-4c5c-b932-8525279d4f0a", :todo/text "hello1"}
              {:db/id 2, :todo/id #uuid"b13319dd-3200-40ec-b8ba-559e404f9aa5", :todo/text "hello2"}
              {:db/id 3, :todo/id #uuid"0ecf7b8a-c3ab-42f7-a1e3-118fcbcee30c", :todo/text "hello3"}
              {:db/id 4, :todo/id #uuid"5860a879-a5e6-4a5f-844b-c8ecc6443f2e", :todo/text "hello4"}])))
  (is (= (vec (sorted-todos dscript-db_))
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

  (transact! @conn_ [(make-todo #uuid"4d245cb4-a6a4-4c28-b605-5b6d451ee35f" "hello5")])

  (is (= (set (all-todos dscript-db_))
        (set [{:db/id 1, :todo/id #uuid"6848eac7-245c-4c5c-b932-8525279d4f0a", :todo/text "hello1"}
              {:db/id 2, :todo/id #uuid"b13319dd-3200-40ec-b8ba-559e404f9aa5", :todo/text "hello2"}
              {:db/id 3, :todo/id #uuid"0ecf7b8a-c3ab-42f7-a1e3-118fcbcee30c", :todo/text "hello3"}
              {:db/id 4, :todo/id #uuid"5860a879-a5e6-4a5f-844b-c8ecc6443f2e", :todo/text "hello4"}
              {:db/id 5, :todo/id #uuid"4d245cb4-a6a4-4c28-b605-5b6d451ee35f", :todo/text "hello5"}])))
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
    (d/db @conn_))
  )

#?(:cljs
   (do
     (defn bytes->mb [amount] (/ amount (js/Math.pow 1000 2)))
     (defn mb-str [amount] (str (bytes->mb amount) "MB"))
     (defn get-mem [] (.. js/performance -memory -usedJSHeapSize))
     (defn pr-mem [] (let [m (get-mem)] (println (mb-str m)) m))))

#?(:cljs (defn memory-test
           []
           (let [start-memory (get-mem)]
             start-memory)))

;; now I want to:
;; in a loop - make a db that has a lot of data
;; {:a1 [1]
;;  :a2 [2}
;; then
;; sut/reg-sub


#?(:cljs
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
     ))


#?(:cljs (defn mem-test-subscribe
           "Tests the memoization cache to ensure it will evict"
           []
           (let [start-mem (get-mem)]
             ;; the default memoization size is 100, so 100 of these should be cached at a time.
             (doseq [x (range 200 ;(js/Math.pow 10 4)
                         )]
               (sut/<sub db [::sub2 (assoc {} x x)])
               )
             (let [end-mem (get-mem)]
               [start-mem end-mem (mb-str (- end-mem start-mem))]))))
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
#?(:cljs
   (comment
     (mem-test)
     (sut/<sub (ratom/atom {::a1 200}) [::a1])
     (println (mb-str (memory-test)))))

(def db-r_ (ratom/atom
             {:root/list-id :root/todos,
              :todo/id      {#uuid"7dea2e3a-9b3d-4d35-a120-d65db43868cb" #:todo{:id    #uuid"7dea2e3a-9b3d-4d35-a120-d65db43868cb",
                                                                                :text  "helo138",
                                                                                :state :incomplete},
                             #uuid"31a54f54-a701-4e92-af91-78447f5294e6" #:todo{:id    #uuid"31a54f54-a701-4e92-af91-78447f5294e6",
                                                                                :text  "helo139",
                                                                                :state :incomplete}},
              :root/todos   [[:todo/id #uuid"7dea2e3a-9b3d-4d35-a120-d65db43868cb"]
                             [:todo/id #uuid"31a54f54-a701-4e92-af91-78447f5294e6"]]}))

(sut/defregsub list-idents (fn [db {:keys [list-id]}] (get db list-id)))
(sut/reg-sub :todo/id (fn [_ {:todo/keys [id]}] id))
(sut/reg-sub :todo/text (fn [db {:todo/keys [id]}]
                          (get-in db [:todo/id id :todo/text])))

(sut/reg-sub ::todo
  (fn [app args]
    {:todo/text (sut/subscribe app [:todo/text args])
     :todo/id   (sut/subscribe app [:todo/id args])})
  (fn [{:todo/keys [id] :as input}]
    (when id input)))

;; todo this is broken because you are deref'ing a sub in the inputs fn - need to refactor this.

(sut/reg-sub ::todos-list
  (fn [r {:keys [list-id]}]
    (let [todo-idents (list-idents r {:list-id list-id})]
      (log/info "todo idents: " todo-idents)
      (mapv (fn [[_ i]] (sut/subscribe r [::todo {:todo/id i}])) todo-idents)))
  (fn [x]
    (log/info "::todos-list x: " x)
    x))

(comment (deref db-r_)
  (sut/<sub db-r_ [::todos-list {:list-id :root/todos}])
  )

(sut/reg-sub-raw ::lookup
  (fn [db_ args]
    (ratom/make-reaction
      (fn [] (get @db_ (:kw args))))))

(deftest args-to-inputs-fn-test
  (let [out (vec (sut/<sub db-r_ [::todos-list {:list-id :root/todos}]))]
    (is (=
          [#:todo{:text "helo138", :id #uuid"7dea2e3a-9b3d-4d35-a120-d65db43868cb"}
           #:todo{:text "helo139", :id #uuid"31a54f54-a701-4e92-af91-78447f5294e6"}]
          out))
    (let [id   #uuid"d3a00f08-f6a3-49e0-bda9-97f245b9feed",
          todo {:todo/id id :todo/text "new one" :todo/state :incomplete}
          _    (swap! db-r_
                 (fn [db]
                   []
                   (-> db
                     (update :root/todos conj [:todo/id id])
                     (update :todo/id assoc id todo))))
          out2 (sut/<sub db-r_ [::todos-list {:list-id :root/todos}])]
      (log/info "out2: " (into [] out2))
      (is (=
            (list #:todo{:text "helo138", :id #uuid"7dea2e3a-9b3d-4d35-a120-d65db43868cb"}
              #:todo{:text "helo139", :id #uuid"31a54f54-a701-4e92-af91-78447f5294e6"}
              #:todo{:text "new one", :id id}) out2))
      (is (= :root/todos (sut/<sub db-r_ [::lookup {:kw :root/list-id}]))))))

(defonce base-db (ratom/atom {:num-one 500 :num-two 5 :num-three 99}))

(sut/reg-sub ::first-sub (fn [db {:keys [kw]}] (kw db)))
(sut/defregsub second-sub :<- [::first-sub] :-> #(+ 100 %))
(sut/defregsub third-sub :<- [::first-sub] :<- [::second-sub] :-> #(reduce + %))

(sut/reg-sub ::first-sub-a (fn [db {:keys [kw]}] (kw db)))
(sut/defregsub second-sub-a :<- [::first-sub-a {:kw :num-three}] :-> #(+ 100 %))
(sut/defregsub third-sub-a :<- [::first-sub-a {:kw :num-three}] :<- [::second-sub-a] :-> #(reduce + %))

(sut/defregsub fourth-sub-a :<- [::first-sub-a] :<- [::second-sub-a] (fn [[a b]] (* a b)))

(deftest sugar-input-with-args
  (testing ":<- inputs are passed the args map"
    (is (= 600 (second-sub base-db {:kw :num-one})))
    (is (= 105 (second-sub base-db {:kw :num-two})))
    (is (= 1100 (third-sub base-db {:kw :num-one})))
    (is (= 110 (third-sub base-db {:kw :num-two}))))

  (testing ":<- inputs with static args map get merged"
    (is (= 199 (second-sub-a base-db)))
    (is (= 105 (second-sub-a base-db {:kw :num-two})))
    (is (= 110 (third-sub-a base-db {:kw :num-two})))
    (is (= 298 (third-sub-a base-db)))
    (is (= 525 (fourth-sub-a base-db {:kw :num-two})))))
