(ns space.matterandvoid.subscriptions.subs
  (:require
    [space.matterandvoid.subscriptions.fulcro :as sut]))

(defonce counter_ (volatile! 0))

(sut/defsub first-sub
  (fn [db] (:first-sub db)))

(sut/defsub second-sub :<- [::first-sub]
  (fn [args]
    (vswap! counter_ inc)
    (+ 10 args)))

(sut/defsub third-sub :<- [::second-sub] #(+ 10 %))

(sut/defsub fourth-sub
  (fn [app] (sut/subscribe app [::first-sub]))
  (fn [first-val] (str first-val)))

(sut/defsub fifth-sub
  (fn [app] {:a (sut/subscribe app [::first-sub])})
  (fn [{:keys [a]}] (+ 20 a)))

(sut/defsub a-sub (fn [db] (db :a)))

(sut/defsub sixth-sub
  (fn [app args] {:a (sut/subscribe app [::fifth-sub])})
  (fn [{:keys [a]}] (+ 20 a)))

;; pass a subscription as a parameter

;; use generative testing to ensure memory usage is constrained


