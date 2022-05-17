(ns todo.fulcro.todo-app2
  (:require
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.algorithms.normalized-state :as nstate]
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]
    [com.fulcrologic.fulcro.application :as fulcro.app]
    [com.fulcrologic.fulcro.components :as c]
    [com.fulcrologic.fulcro.mutations :as mut :refer [defmutation]]
    [com.fulcrologic.fulcro.dom :as dom]
    [space.matterandvoid.subscriptions.fulcro :as subs :refer [defsc defsub reg-sub]]
    [reagent.ratom :as r]
    [taoensso.timbre :as log]))

(defn make-todo
  ([text] (make-todo (random-uuid) text))
  ([id text] {:todo/id id :todo/text text :todo/state :incomplete}))

(defn make-comment
  ([text] (make-comment (random-uuid) text (js/Date.)))
  ([id text at] {:comment/id id :comment/text text :comment/at at}))

(defn toggle-todo [todo] (update todo :todo/state {:incomplete :complete :complete :incomplete}))

(comment
  (toggle-todo (toggle-todo (make-todo (random-uuid) "hi"))))

(defsub all-todos :-> #(-> % :todo/id vals))
(defsub complete-todos :<- [::all-todos] :-> #(filter (comp #{:complete} :todo/state) %))
(defsub incomplete-todos :<- [::all-todos] :-> #(filter (comp #{:incomplete} :todo/state) %))

(reg-sub :todo/id (comp :todo/id second))
(reg-sub :todo/text (fn [db {:todo/keys [id]}] (get-in db [:todo/id id :todo/text])))

(defsub todo
  (fn [app args]
    (log/info "IN ::todo sub inputs fn")
    {:todo/text (subs/subscribe app [:todo/text args])
     :todo/id   (subs/subscribe app [:todo/id args])})
  (fn [{:todo/keys [id] :as input}]
    (log/info "IN ::todo sub computation fn")
    (when id input)))

(defsub list-idents
  (fn [db {:keys [list-id] :as args}]
    (log/info "list-idents subscription args: " args)
    (log/info "return valu: " (get db list-id))
    (get db list-id)))

;; anytime you have a list of idents in fulcro the subscription pattern is to
;; have input signals that subscribe to layer 2 subscriptions

(reg-sub ::todo-table :-> (fn [db] (-> db :todo/id)))

;; now any subscriptions that use ::todo-table as an input signal will only update if todo-table's output changes.

(defsub todos-list :<- [::list-idents] :<- [::todo-table]
  (fn [[idents table]] (mapv #(get table (second %)) idents)))

(defsub todos-list-orig
  ;(fn [app args] [(subs/subscribe app [::list-idents args]) (subs/subscribe app [::todo-table args])])
  (fn [app args]
    (mapv (fn [v]
           (log/info "sub to: " (update v 1 merge args))
           (subs/subscribe app (update v 1 merge args)))
      [[::list-idents] [::todo-table]]))
  (fn [[idents table]]
    (log/info "subsc ::todo-list-expanded idents" idents)
    (log/info "subsc ::todo-list-expanded table" table)
    (mapv #(get table (second %)) idents)))

(defsub todos-total :<- [::todos-list] :-> count)

(defmutation change-todo-text
  [{:keys [id text]}]
  (action [{:keys [state]}]
    (swap! state assoc-in [:todo/id id :todo/text] text)))

(defn change-todo-text! [this args] (c/transact! this [(change-todo-text args)]))

(defmutation rm-random-todo [_]
  (action [{:keys [state]}]
    (when-let [id (-> @state :todo/id keys first)]
      (swap! state nstate/remove-entity [:todo/id id]))))

(defn rm-random-todo! [this] (c/transact! this [(rm-random-todo)]))

(declare Todo)

(defn add-random-todo! [app]
  (merge/merge-component! (c/any->app app) Todo (make-todo (str "todo-" (rand-int 1000))) :append [:root/todos]))

(defsc Todo [this {:todo/keys [text state completed-at]}]
  {:query         [:todo/id :todo/text :todo/state :todo/completed-at fs/form-config-join]
   :ident         :todo/id
   ::fs/fields    #{:todo/text :todo/state}
   :initial-state (fn [text] (make-todo (or text "")))}
  (log/info "Rendering todo item: " text)
  (dom/div
    {}
    (dom/label
      "Text:"
      (dom/input {:value text}))
    (dom/div "Todo:" (dom/div text))
    (dom/div "status: " (pr-str state))))

(macroexpand
  '(defsc Todo [this {:todo/keys [text state completed-at]}]
    {:query         [:todo/id :todo/text :todo/state :todo/completed-at fs/form-config-join]
     :ident         :todo/id
     ::fs/fields    #{:todo/text :todo/state}
     :initial-state (fn [text] (make-todo (or text "")))}
    (log/info "Rendering todo item: " text)
    (dom/div
      {}
      (dom/label
        "Text:"
        (dom/input {:value text}))
      (dom/div "Todo:" (dom/div text))
      (dom/div "status: " (pr-str state)))))

(def ui-todo (c/computed-factory Todo {:keyfn :todo/id}))

(defsc TodosTotal [this {:keys [list-id]}]
  {:query [:list-id]}
  (dom/h3 "Total todos: " (todos-total this {:list-id list-id})))

(def ui-todos-total (c/factory TodosTotal))

(defsc TodoList [this {:keys [list-id]}]
  {:ident (fn [] [:component/id ::todo-list])
   :query [:list-id]}
  (log/info "In TodoList render fn change10")

  (let [todos #_[]
        (todos-list this {:list-id list-id})]
    ;(def t' todos)
    (dom/div {}
      (dom/h1 "Todos")

      (dom/p "hi")
      (dom/button {:style {:padding 20} :onClick #(add-random-todo! this)} "Add")

      (ui-todos-total {:list-id list-id})
      (dom/pre (pr-str todos))
      (dom/hr)
      (map ui-todo todos))))

(def ui-todo-list (c/computed-factory TodoList))

(defsc Root [this {:root/keys [list-id]}]
  {:initial-state {:root/list-id :root/todos}
   :query         [:root/list-id]}
  (dom/div {} (ui-todo-list {:list-id list-id})))

(defonce fulcro-app (subs/fulcro-app))

(defmutation force-draw [_]
  (action [{:keys [state]}] (swap! state update :draw-count inc)))

(defn ^:export init []
  (fulcro.app/mount! fulcro-app Root js/app)

  ;(c/transact! fulcro-app [(force-draw)])

  ;(fulcro.app/force-root-render! [fulcro-app])
  )
(defn  ^:dev/after-load refresh []
  (fulcro.app/unmount! fulcro-app)
  (fulcro.app/mount! fulcro-app Root js/app {:initialize-state? false})
  (subs/clear-subscription-cache! fulcro-app)
  ;(c/transact! fulcro-app [(force-draw)])

  ;(fulcro.app/force-root-render! [fulcro-app])
  )
(comment (fulcro.app/current-state fulcro-app))



;; todo:
;; add input form instead of merge-comp in the repl
;; add mutation
