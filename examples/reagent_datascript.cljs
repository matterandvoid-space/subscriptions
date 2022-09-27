(ns reagent-datascript
  (:require
    [space.matterandvoid.subscriptions.core :refer [reg-sub <sub]]
    [datascript.core :as d]
    [reagent.dom :as rd]
    [reagent.ratom :as ra]))

(def schema {:todo/id {:db/unique :db.unique/identity}})
(defonce conn (d/create-conn schema))
(defonce dscript-db_ (ra/atom (d/db conn)))

(defn make-todo [id text] {:todo/id id :todo/text text})
(def todo1 (make-todo #uuid"6848eac7-245c-4c5c-b932-8525279d4f0a" "todo1"))
(def todo2 (make-todo #uuid"b13319dd-3200-40ec-b8ba-559e404f9aa5" "todo2"))
(def todo3 (make-todo #uuid"0ecf7b8a-c3ab-42f7-a1e3-118fcbcee30c" "todo3"))
(def todo4 (make-todo #uuid"5860a879-a5e6-4a5f-844b-c8ecc6443f2e" "todo4"))

;; This is the main thing to notice - by changing the ratom, any views subscribing to
;; the data will upate

(defn transact! [conn data]
  (d/transact! conn data)
  (reset! dscript-db_ (d/db conn)))

(defonce transact-data
  (transact! conn [todo1 todo2 todo3 todo4]))

(reg-sub ::all-todos
  :-> (fn [db] (d/q '[:find [(pull ?e [*]) ...] :where [?e :todo/id]] db)))

(reg-sub ::sorted-todos :<- [::all-todos] :-> (partial sort-by :todo/text))
(reg-sub ::rev-sorted-todos :<- [::sorted-todos] :-> reverse)
(reg-sub ::sum-lists :<- [::all-todos] :<- [::rev-sorted-todos] :-> (partial mapv count))

;; if you were to use these inside a reagent view the view will render when the data changes.
(comment
  (<sub dscript-db_ [::all-todos])
  (<sub dscript-db_ [::sorted-todos])
  (<sub dscript-db_ [::rev-sorted-todos]))

;; use the transact helper to ensure the ratom is updated as well as the db

(defn create-todo! []
  (transact! conn [(make-todo (random-uuid) (str "another todo" (rand-int 1000)))]))

(defn sorted-todos-ui []
  (let [ts (<sub dscript-db_ [::sorted-todos])]
   [:div {:style {:width "49%"}}
    [:h2 "Sorted by text"]
    (for [t ts]
      ^{:key (:todo/id t)}
      [:div
       [:hr]
       [:h3 (:todo/text t)]
       [:pre (:todo/id t)]])]))

(defn main []
  (let [ts (<sub dscript-db_ [::all-todos])]
    [:div
     [:button {:on-click create-todo!} "create todo"]
     [:p "I am a component!"]
     [:div {:style {:display "flex"}}
      [sorted-todos-ui]
      [:div {:style {:width "49%"}}
       [:h2 "Unsorted"]
       (for [t ts]
         ^{:key (:todo/id t)}
         [:div
          [:hr]
          [:h3 (:todo/text t)]
          [:pre (:todo/id t)]])]]]))

(defn ^:export ^:dev/after-load init [] (rd/render [main] js/app))
