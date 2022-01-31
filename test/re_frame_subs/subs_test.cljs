(ns re-frame-subs.subs-test
  (:require
    [re-frame-subs.subs :as sut]
    [cljs.test :refer [deftest is testing]]))


(deftest memoize-fn-test
  (let [counter (volatile! 0)
        add     (fn add [x y]
                  (vswap! counter inc)
                  (println "EXECUTING") (+ x y))
        mem-add (sut/memoize-fn 2 3 add)]
    (mem-add 1 1) (mem-add 1 1) (mem-add 1 1) (mem-add 1 1) (mem-add 1 1)
    (is (= 1 @counter))))
