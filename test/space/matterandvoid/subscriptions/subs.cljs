(ns space.matterandvoid.subscriptions.subs
  (:require
    [com.fulcrologic.fulcro.application :as fulcro.app]
    [space.matterandvoid.subscriptions.fulcro :as sut]))

(defonce app (fulcro.app/fulcro-app {:initial-db {:first 500 :a "hi"}}))

(sut/defsub first-sub
  (fn [db] (:first-sub db)))

(sut/defsub second :<- [::first]
  (fn [args]
    (vswap! counter_ inc)
    (+ 10 args)))

(sut/defsub ::third :<- [::second] #(+ 10 %))

(sut/defsub ::fourth
  (fn [db args] (sut/subscribe app [::first]))
  (fn [first-val] (str first-val)))

(sut/defsub ::fifth
  (fn [db args] {:a (sut/subscribe app [::first])})
  (fn [{:keys [a]}] (+ 20 a)))

(sut/defsub ::a (fn [db] (db :a)))

(sut/defsub ::sixth
  (fn [db [_ args]] {:a (sut/subscribe app [::fifth])})
  (fn [{:keys [a]}] (+ 20 a)))

;; pass a subscription as a parameter

;; use generative testing to ensure memory usage is constrained

(deftest sub-0-test
  (let [out1 (sut/<sub app [::first])
        out2 (sut/<sub app [::second])
        out3 (sut/<sub app [::third])
        out4 (sut/<sub app [::fourth])
        out5 (sut/<sub app [::fifth])
        a (sut/<sub app [::a])]
    (is (= out1 500))
    (is (= out2 510))
    (is (= (sut/<sub app [::second]) 510))
    (is (= (sut/<sub app [::second]) 510))
    (is (= out3 520))
    (is (= out4 "500"))
    (is (= out5 520))
    (is (= a "hi"))
    (is (= @counter_ 1))))


(deftest subs-arg-must-be-map-test
  (is (thrown-with-msg? js/Error #"Query must contain only one map" (sut/<sub app [::fifth 'a 'b])))
  (is (thrown-with-msg? js/Error #"Args to the query vector must be one map." (sut/<sub app [::fifth 'b]))))

(deftest def-sub-test
  (let [out1 (first app)
        out2 ([::second])
        out3 (sut/<sub app [::third])
        out4 (sut/<sub app [::fourth])
        out5 (sut/<sub app [::fifth])
        a (sut/<sub app [::a])]
    (is (= out1 500))
    (is (= out2 510))
    (is (= (sut/<sub app [::second]) 510))
    (is (= (sut/<sub app [::second]) 510))
    (is (= out3 520))
    (is (= out4 "500"))
    (is (= out5 520))
    (is (= a "hi"))
    (is (= @counter_ 1))))

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
        test-sub-e (sut/subscribe app [:e-first-sub])]
    (is (= nil @test-sub-c))
    (reset! (::fulcro.app/state-atom app) {:a 1 :b 2 :c :foo :d [1 2] :e [3 4]})
    (is (= {:a 1 :b 2} @test-sub))
    (is (= :foo @test-sub-c))
    (is (= 1 @test-sub-d))
    (is (= 3 @test-sub-e))))

(deftest test-sub-macros-=>
  "test the syntactical sugar for input signals and query vector arguments"
  (sut/reg-sub :a-sub :-> :a)
  (sut/reg-sub :b-sub :-> :b)
  (sut/reg-sub :test-a-sub :<- [:a-sub] :=> vector)
  ;; test for equality of input signal and query parameter
  (sut/reg-sub :test-b-sub :<- [:b-sub] :=> =)
  (let [test-a-sub (sut/subscribe app [:test-a-sub :c])
        test-b-sub (sut/subscribe app [:test-b-sub 2])]
    (is (= [1 :c] @test-a-sub))
    (is (= true @test-b-sub))))
