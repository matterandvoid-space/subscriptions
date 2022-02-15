(ns space.matterandvoid.subscriptions.subs-test
  (:require
    [space.matterandvoid.subscriptions.subs :as sut]
    [cljs.test :refer [deftest is testing]]))

(deftest memoize-fn-test
  (let [counter (volatile! 0)
        add     (fn add [x y]
                  (vswap! counter inc)
                  (println "EXECUTING") (+ x y))
        mem-add (sut/memoize-fn 2 3 add)]
    (mem-add 1 1) (mem-add 1 1) (mem-add 1 1) (mem-add 1 1) (mem-add 1 1)
    (is (= 1 @counter))))

;; setup a test environment and pass all of the callbacks needed
;(deftest test-reg-sub-clj-repl
;  (sut/reg-sub :a-sub (fn [db _] (:a db)))
;  (sut/reg-sub :b-sub (fn [db _] (:b db)))
;  (sut/reg-sub :a-b-sub (fn [_ _] (mapv subs/subscribe [[:a-sub] [:b-sub]])) (fn [[a b] _] {:a a :b b}))
;
;  (let [test-sub (sut/subscribe [:a-b-sub])]
;    (reset! db/app-db {:a 1 :b 2})
;    (is (= {:a 1 :b 2} @test-sub))
;    (swap! db/app-db assoc :b 3)
;    (is (= {:a 1 :b 3} @test-sub))))
