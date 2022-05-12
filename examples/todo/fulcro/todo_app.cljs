(ns todo.fulcro.todo-app
  (:require
    [com.fulcrologic.fulcro.application :as fulcro.app]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.dom :as dom]
    [space.matterandvoid.subscriptions.fulcro :as subs :refer [defsc defsub]]
    [com.fulcrologic.fulcro.components :as c]))


;; 1. make a fulcro app
;; 2. write the views
;; 3. write the mutations
;; 4. write the subs
;; that's it


(defn make-todo
  ([text] (make-todo (random-uuid) text))
  ([id text] {:todo/id id :todo/text text :todo/state :incomplete}))

(defn make-comment
  ([text] (make-comment (random-uuid) text (js/Date.)))
  ([id text at] {:comment/id id :comment/text text :comment/at at}))

(defn toggle-todo [todo] (update todo :todo/state {:incomplete :complete :complete :incomplete}))

(comment
  (toggle-todo
    (toggle-todo (make-todo (random-uuid) "hi"))))

(defsub all-todos :-> #(-> % :todo/id vals))
(defsub complete-todos :<- [::all-todos] :-> #(filter (comp #{:complete} :todo/state) %))
(defsub incomplete-todos :<- [::all-todos] :-> #(filter (comp #{:incomplete} :todo/state) %))

(defsc Comment [this {:comment/keys [text at]}]
  {:query         [:comment/id :comment/text :comment/at]
   :ident         :comment/id
   :initial-state (fn [text] (make-comment text))}
  (dom/div
    (dom/div "Comment: ") (dom/p text) (dom/p (str "at " (.toLocaleString at)))))

(def ui-comment (c/computed-factory Comment {:keyfn :comment/id}))

(defsc Todo [this props]
  {:query         [:todo/id :todo/text :todo/state :todo/completed-at]
   :ident         :todo/id
   :initial-state (fn [text] (make-todo text))})

(def ui-todo (c/computed-factory Todo {:keyfn :todo/id}))

;; todo copy the example from the fulcro codebase - where a list has an id
;; this way you can use 'this' in the subscription to get the current value of the list
;; when computing a subscription and to maintain multiple lists.
;; with filtering sorting etc handled by one parameterized sub.

(defsc TodoList [this props]
  {:ident         (fn [] [:component/id ::todo-list])
   :query         []
   ::subs/signals (fn [] {:todos          [::all-todos]
                          :complete-todos [::complete-todos]})
   }
  (let [{:keys [todos complete-todos]} (subs/signals-map this)]
    (dom/div
      (dom/h1 "Todos")
      (map ui-todo todos))))

(def ui-todo-list (c/computed-factory TodoList))

(defsc Root [this {:keys [todo-list]}]
  {:query [{:todo-list (c/get-query TodoList)}]}
  (dom/div "App:"
    (ui-todo-list todo-list)))

(make-todo "one")
(make-todo "two")
(make-todo "three")
(make-todo "four")
(make-todo "five")
(defonce fulcro-app (subs/fulcro-app {:initial-db {}}))
(defn ^:export ^:dev/after-load init [] (fulcro.app/mount! fulcro-app Root js/app))

(comment
  (as-> fulcro-app XX
    ;(merge/merge-component! XX Todo (make-todo "helo"))
    (merge/merge-component! XX Todo (make-todo "helo") :append [:root/todos])
    (fulcro.app/current-state fulcro-app))

  (all-todos fulcro-app)
  (complete-todos fulcro-app)
  (incomplete-todos fulcro-app)
  )
