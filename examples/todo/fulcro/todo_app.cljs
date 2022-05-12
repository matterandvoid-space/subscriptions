(ns todo.fulcro.todo-app
  (:require
    [com.fulcrologic.fulcro.application :as fulcro.app]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.dom :as dom]
    [space.matterandvoid.subscriptions.fulcro :as subs :refer [defsc defsub reg-sub]]
    [com.fulcrologic.fulcro.components :as c]
    [taoensso.timbre :as log]))


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


;; so i'm thinking in this demo all data that is _read_ to draw the UI comes thru subscriptions
;; the defsc and queries and idents are use for normalization.
;; don't try to please people using fulcro for other purposes

;; so these sort of subscriptions can be add to defsc macro

(reg-sub :todo/id
  (fn [db [_ {:todo/keys [id]}]]
    (when (contains? (:todo/id db) id)
      id)))

(reg-sub :todo/text
  (fn [db [_ {:todo/keys [id]}]]
    (get-in db [:todo/id id :todo/text])))

;(reg-sub ::habit/record-log-idents
;  (fn [db [_ {::habit/keys [id]}]]
;    (get-in db (habit/ident id ::habit/record-log))))
;
;(reg-sub ::habit/record-log
;  (fn [[_ args]]
;    ;(log/info "computing signals for ::habit/record-log")
;    {::habit/record-log (mapv #(sub/subscribe SPA [::record-log.subs/record-log (apply hash-map %)])
;                              (sub/<sub SPA [::habit/record-log-idents args]))})
;  (fn [{::habit/keys [record-log]} [_ {::habit/keys [id]}]]
;    ;(log/info "computing record log: " record-log)
;    record-log))

(reg-sub ::todo
  (fn [app [_ args]]
    (log/info "::todo db: " app)
    (log/info "::todo args: " args)
    ;(log/info "signas, input: " args)
    ;; so this is the structure to end up with
    ;; it's a tree that mirrors the query - at this level you don't care if a piece of data is a join or not
    ;; the implementation of the subscription will deal with that
    ;; for example - fetching all the record-logs
    {:todo/text (subs/subscribe app [:todo/text args])
     :todo/id   (subs/subscribe app [:todo/id args])})
  (fn [{:todo/keys [id text] :as input}]
    (def input' input)
    (log/info "habit sub input: " input)
    (when id
      ;(habit/make-habit input)
      input)
    ))
(comment (subs/<sub fulcro-app [::todo {:todo/id #uuid"703ecd9c-1ebe-47e2-8ed0-adad1f2642de"}]))

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
;
;(defsc TodoList [this {:list/keys [id items filter title] :as props}]
;  {:initial-state {:list/id 1 :ui/new-item-text "" :list/items [] :list/title "main" :list/filter :list.filter/none}
;   :ident         :list/id
;   :query         [:list/id :ui/new-item-text {:list/items (comp/get-query TodoItem)} :list/title :list/filter]}
;  (let [num-todos       (count items)
;        completed-todos (filterv :item/complete items)
;        num-completed   (count completed-todos)
;        all-completed?  (every? :item/complete items)
;        filtered-todos  (case filter
;                          :list.filter/active (filterv (comp not :item/complete) items)
;                          :list.filter/completed completed-todos
;                          items)
;        delete-item     (fn [item-id] (comp/transact! this `[(api/todo-delete-item ~{:list-id id :id item-id})]))]
;    (dom/div {}
;      (dom/section :.todoapp {}
;        (header this title)
;        (when (pos? num-todos)
;          (dom/div {}
;            (dom/section :.main {}
;              (dom/input {:type      "checkbox"
;                          :className "toggle-all"
;                          :checked   all-completed?
;                          :onClick   (fn [] (if all-completed?
;                                              (comp/transact! this `[(api/todo-uncheck-all {:list-id ~id})])
;                                              (comp/transact! this `[(api/todo-check-all {:list-id ~id})])))})
;              (dom/label {:htmlFor "toggle-all"} "Mark all as complete")
;              (dom/ul :.todo-list {}
;                (map #(ui-todo-item % {:delete-item delete-item}) filtered-todos)))
;            #_(filter-footer this num-todos num-completed))))
;      #_(footer-info))))
;
;(def ui-todo-list (c/factory TodoList))

;(defsc Root [this {:keys [todo-list]}]
;  {:query [{:todo-list (c/get-query TodoList)}]}
;  (dom/div "App:"
;    (ui-todo-list todo-list)))

(defsc Root [this {:root/keys [todo]}]
  {:initial-state {:root/todo {}}
   :query         [{:root/todo (c/get-query TodoList)}]}
  (dom/div {} (ui-todo-list todo)))

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
