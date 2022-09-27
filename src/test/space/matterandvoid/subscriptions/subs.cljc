(ns space.matterandvoid.subscriptions.subs
  (:require
    [space.matterandvoid.subscriptions.fulcro :as sut]))

(defonce counter_ (volatile! 0))

(sut/defregsub first-sub
  (fn [db] (:first-sub db)))

(sut/defregsub second-sub :<- [::first-sub]
  (fn [args]
    (vswap! counter_ inc)
    (+ 10 args)))

(sut/defregsub third-sub :<- [::second-sub] #(+ 10 %))

(sut/defregsub fourth-sub
  (fn [app] (sut/subscribe app [::first-sub]))
  (fn [first-val] (str first-val)))

(sut/defregsub fifth-sub
  (fn [app] {:a (sut/subscribe app [::first-sub])})
  (fn [{:keys [a]}] (+ 20 a)))

(sut/defregsub a-sub (fn [db] (db :a)))

(sut/defregsub sixth-sub
  (fn [app args] {:a (sut/subscribe app [::fifth-sub])})
  (fn [{:keys [a]}] (+ 20 a)))

;; pass a subscription as a parameter

;; use generative testing to ensure memory usage is constrained


