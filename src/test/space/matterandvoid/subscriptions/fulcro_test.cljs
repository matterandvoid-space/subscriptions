(ns space.matterandvoid.subscriptions.fulcro-test
  (:require
    [cljs.test :refer [deftest is testing use-fixtures]]
    [space.matterandvoid.subscriptions.subs :as subs]
    [com.fulcrologic.fulcro.application :as fulcro.app]
    [space.matterandvoid.subscriptions.fulcro :as sut]))

(enable-console-print!)
(defonce counter_ (volatile! 0))

(use-fixtures :each
  {:after (fn [] (vreset! counter_ 0))})

(defonce app (sut/with-subscriptions
               (fulcro.app/fulcro-app {:initial-db {:first     500
                                                    :first-sub 500
                                                    :a         "hi"}})))

(sut/reg-sub ::first
  (fn [db] (:first db)))

(sut/reg-sub ::second :<- [::first]
  (fn [args]
    (vswap! counter_ inc)
    (+ 10 args)))

(sut/reg-sub ::third :<- [::second] #(+ 10 %))

(sut/reg-sub ::fourth
  (fn [app* args] (sut/subscribe app* [::first]))
  (fn [first-val] (str first-val)))

(sut/reg-sub ::fifth
  (fn [db args] {:a (sut/subscribe app [::first])})
  (fn [{:keys [a]}] (+ 20 a)))

(sut/reg-sub ::a (fn [db] (db :a)))

(sut/reg-sub ::sixth
  (fn [db args] {:a (sut/subscribe app [::fifth])})
  (fn [{:keys [a]}] (+ 20 a)))

;; pass a subscription as a parameter

;; use generative testing to ensure memory usage is constrained

(deftest sub-0-test
  (let [out1 (sut/<sub app [::first])
        out2 (sut/<sub app [::second])
        out3 (sut/<sub app [::third])
        out4 (sut/<sub app [::fourth])
        out5 (sut/<sub app [::fifth])
        a    (sut/<sub app [::a])]
    (is (= out1 500))
    (is (= out2 510))
    (is (= (sut/<sub app [::second]) 510))
    (is (= (sut/<sub app [::second]) 510))
    (is (= out3 520))
    (is (= out4 "500"))
    (is (= out5 520))
    (is (= a "hi"))
    (is (= @counter_ 1))))

;(deftest subs-arg-must-be-map-test
;  (is (thrown-with-msg? js/Error #"Query must contain only one map" (sut/<sub app [::fifth 'a 'b])))
;  (is (thrown-with-msg? js/Error #"Args to the query vector must be one map." (sut/<sub app [::fifth 'b]))))
;
(comment
  (subs/fifth-sub app)
  (subs/first-sub app)
  )
(deftest def-sub-test
  (let [out1 (subs/first-sub app)
        out2 (subs/second-sub app)
        out3 (subs/third-sub app)
        out4 (subs/fourth-sub app)
        out5 (subs/fifth-sub app)
        out6 (subs/sixth-sub app)
        a    (subs/a-sub app)]
    (is (= out1 500))
    (is (= out2 510))
    (is (= (sut/<sub app [::subs/second-sub]) 510))
    (is (= (sut/<sub app [::subs/second-sub]) 510))
    (is (= 510 (subs/second-sub app)))
    (is (= 510 (subs/second-sub app)))
    (is (= out3 520))
    (is (= out4 "500"))
    (is (= out5 520))
    (is (= out6 540))
    (is (= a "hi"))
    (is (= @subs/counter_ 1))))

(deftest test-sub-macros-->
  "test the syntactical sugar for input signal"
  (sut/reg-sub :a-sub :-> :a)
  (sut/reg-sub :b-sub :-> :b)
  (sut/reg-sub :c-sub :-> :c)
  (sut/reg-sub :d-sub :-> :d)
  (sut/reg-sub :d-first-sub :<- [:d-sub] :-> first)

  ;; variant of :d-first-sub without an input parameter
  (sut/defsub e-first-sub :-> (comp first :e))
  ;; test for equality
  (sut/reg-sub :c-foo?-sub :<- [:c-sub] :-> #{:foo})

  (sut/reg-sub
    :a-b-sub
    :<- [:a-sub]
    :<- [:b-sub]
    :-> (partial zipmap [:a :b]))

  (let [test-sub   (sut/subscribe app [:a-b-sub])
        test-sub-c (sut/subscribe app [:c-foo?-sub])
        test-sub-d (sut/subscribe app [:d-first-sub])
        test-sub-e (sut/subscribe app [::e-first-sub])]
    (is (= nil @test-sub-c))
    (reset! (::fulcro.app/state-atom app) {:a 1 :b 2 :c :foo :d [1 2] :e [3 4]})
    (is (= {:a 1 :b 2} @test-sub))
    (is (= :foo @test-sub-c))
    (is (= 1 @test-sub-d))
    (is (= 3 @test-sub-e))
    (is (= 3 (e-first-sub app)))))

; test the syntactical sugar for input signals and query vector arguments
(deftest test-sub-macros->
  (reset! (::fulcro.app/state-atom app) {:a 1 :b 2 :c :foo :d [1 2] :e [3 4]})
  (sut/reg-sub :a-sub :-> :a)
  (sut/reg-sub :b-sub :-> :b)
  (sut/reg-sub :test-a-sub :<- [:a-sub] vector)
  ;; test for equality of input signal and query parameter
  (sut/reg-sub :test-b-sub :<- [:b-sub] (comp = :arg))
  (let [test-a-sub (sut/subscribe app [:test-a-sub {:arg :c}])
        test-b-sub (sut/subscribe app [:test-b-sub {:arg 2}])]
    (is (= [1 {:arg :c}] @test-a-sub))
    (is (= true @test-b-sub))))
