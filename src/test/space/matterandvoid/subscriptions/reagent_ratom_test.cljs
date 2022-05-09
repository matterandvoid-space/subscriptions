(ns space.matterandvoid.subscriptions.reagent-ratom-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [reagent.ratom :as ratom]
    [space.matterandvoid.subscriptions.reagent-ratom :as sut]))

(sut/reg-sub :hello
  (fn [db] (:hello db)))

(sut/defsub sub2 :-> :sub2)

(defonce db (ratom/atom {:sub2 500 :hello "hello"}))

(deftest basic-test
  (is (= 500 (sub2 db)))
  (is (= 500 (sub2 db {})))
  (is (thrown-with-msg? js/Error #"Args to the query vector must be one map" (= 500 (sub2 db 13))))
  (is (= 500 (sut/<sub db [::sub2])))
  (is (= 500 @(sut/subscribe db [::sub2])))
  (is (= "hello" (sut/<sub db [:hello])))
  (is (= "hello" @(sut/subscribe db [:hello]))))

(comment
  (swap! db update :sub2 inc)
  (swap! db (fn [d] (assoc d :sub2 500)))
  (swap! db assoc :sub2 500)
  (sub2 db)
  @(sut/subscribe db [:hello])
  )
