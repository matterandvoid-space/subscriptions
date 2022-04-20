(ns space.matterandvoid.subscriptions.fulcro-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [com.fulcrologic.fulcro.application :as fulcro.app]
    [space.matterandvoid.subscriptions.fulcro :as sut]))

(enable-console-print!)

(defonce app (fulcro.app/fulcro-app {:initial-db {:first 500
                                                  :a "hi"}}))

(sut/reg-sub ::first
  (fn [db] (:first db)))

(defonce counter_ (volatile! 0))
(sut/reg-sub ::second :<- [::first]
  (fn [args]
    (vswap! counter_ inc)
    (+ 10 args)))

(sut/reg-sub ::third :<- [::second] #(+ 10 %))

(sut/reg-sub ::fourth
  (fn [db args] (sut/subscribe app [::first]))
  (fn [first-val] (str first-val)))

(sut/reg-sub ::fifth
  (fn [db args] {:a (sut/subscribe app [::first])})
  (fn [{:keys [a]}] (+ 20 a)))

(sut/reg-sub ::a (fn [db] (db :a)))

(sut/reg-sub ::sixth
  (fn [db [_ args]] {:a (sut/subscribe app [::fifth])})
  (fn [{:keys [a]}] (+ 20 a)))

(deftest sub-0-test
  (let [out1 (sut/<sub app [::first])
        out2 (sut/<sub app [::second])
        out3 (sut/<sub app [::third])
        out4 (sut/<sub app [::fourth])
        out5 (sut/<sub app [::fifth])
        a (sut/<sub app [::a])
        ]
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
