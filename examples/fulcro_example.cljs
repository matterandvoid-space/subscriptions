(ns fulcro-example
  (:require
    ["react-dom/client" :as react-dom]
    ["react" :as react]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    [com.fulcrologic.fulcro.algorithms.normalized-state :as nstate]
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]
    [com.fulcrologic.fulcro.application :as fulcro.app]
    [com.fulcrologic.fulcro.components :as c :refer [defsc]]
    [com.fulcrologic.fulcro.raw.components :as rc]
    [com.fulcrologic.fulcro.mutations :as mut :refer [defmutation]]
    [com.fulcrologic.fulcro.dom :as dom]
    [space.matterandvoid.subscriptions.fulcro :as subs :refer [defregsub reg-sub]]
    [space.matterandvoid.subscriptions.fulcro-eql :as f.eql]
    [space.matterandvoid.subscriptions.fulcro-components :refer [with-reactive-subscriptions]]
    [goog.object :as g]
    [taoensso.timbre :as log]))

;; Subscriptions
;=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defregsub all-todos :-> #(-> % :todo/id vals))
(defregsub complete-todos :<- [::all-todos] :-> #(filter (comp #{:complete} :todo/state) %))
(defregsub incomplete-todos :<- [::all-todos] :-> #(filter (comp #{:incomplete} :todo/state) %))

(reg-sub :todo/id (comp :todo/id second))
(reg-sub :todo/text (fn [db {:todo/keys [id]}] (get-in db [:todo/id id :todo/text])))

(defregsub todo
  (fn [app args]
    (log/info "IN ::todo sub inputs fn")
    {:todo/text (subs/subscribe app [:todo/text args])
     :todo/id   (subs/subscribe app [:todo/id args])})
  (fn [{:todo/keys [id] :as input}]
    (log/info "IN ::todo sub computation fn")
    (when id input)))

(defregsub list-idents (fn [db {:keys [list-id]}] (get db list-id)))

;; anytime you have a list of idents in fulcro the subscription pattern is to
;; have input signals that subscribe to layer 2 subscriptions

(reg-sub ::todo-table :-> (fn [db] (-> db :todo/id)))

;; now any subscriptions that use ::todo-table as an input signal will only update if todo-table's output changes.

(defregsub todos-list :<- [::list-idents] :<- [::todo-table]
  (fn [[idents table]]
    (mapv #(get table (second %)) idents)))

(defregsub todos-total :<- [::todos-list] :-> count)

;; Mutations
;=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defn make-todo
  ([text] (make-todo (random-uuid) text))
  ([id text] {:todo/id id :todo/text text :todo/state :incomplete}))

(defn make-comment
  ([text] (make-comment (random-uuid) text (js/Date.)))
  ([id text at] {:comment/id id :comment/text text :comment/at at}))

(defn toggle-todo [todo] (update todo :todo/state {:incomplete :complete :complete :incomplete}))

(comment (toggle-todo (toggle-todo (make-todo (random-uuid) "hi"))))

(defmutation change-todo-text
  [{:keys [id text]}]
  (action [{:keys [state]}] (swap! state assoc-in [:todo/id id :todo/text] text)))

(defn change-todo-text! [this args] (c/transact! this [(change-todo-text args)]))

(defmutation rm-random-todo [_]
  (action [{:keys [state]}]
    (when-let [id (-> @state :todo/id keys first)]
      (swap! state nstate/remove-entity [:todo/id id]))))

(defn rm-random-todo! [this] (c/transact! this [(rm-random-todo)]))

(declare Todo)

(defn add-random-todo! [app]
  (log/info "ADD Random todo")
  (merge/merge-component! (c/any->app app) Todo (make-todo (str "todo-" (rand-int 1000))) :append [:root/todos]))

;; Components
;=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defsc Todo [this {:todo/keys [text state completed-at]}]
  {:query         [:todo/id :todo/text :todo/state :todo/completed-at fs/form-config-join]
   :ident         :todo/id
   ::fs/fields    #{:todo/text :todo/state}
   :initial-state (fn [text] (make-todo (or text "")))}
  (dom/div
    {}
    (dom/label "Text:" (dom/input {:value text}))
    (dom/div "Todo:" (dom/div text))
    (dom/div "status: " (pr-str state))))

(def ui-todo (c/computed-factory Todo {:keyfn :todo/id}))


(defsc TodosTotal [this {:keys [list-id]}] {}
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
      (dom/h1 "Todos3")

      (dom/p "hi")
      (dom/button {:style {:padding 20 :margin "0 1rem"} :onClick #(add-random-todo! this)} "Add")

      (when (> (todos-total this {:list-id list-id}) 0)
        (dom/button {:style {:padding 20} :onClick #(rm-random-todo! this)} "Remove"))

      (ui-todos-total {:list-id list-id})
      (dom/pre (pr-str todos))
      (dom/hr)
      (map ui-todo todos))))

(defn reactive [app-or-this & a]
  (let [app (c/any->app app-or-this)]
    ;; in this model you do not deref in the render
    ;; you deref after tx
    ))

;; working on injecting derived state into fulcro app using application hook
;; transact! =>
;; the mutation  (or multiple) fire - the user's mutate function calls swap! (reset!) on the atom (ratom)
;; at this point the subscriptions which the user cares about have not been deref'd - but the subscriptions were
;; created (reg-sub) -> so then we deref the subscriptions -> this would result in new computations -> in the reactive
;; update callback we want to update the state - but now I'm not sure.

(defsc TodoList2 [this {:keys [list-id]}]
  {:ident (fn [] [:component/id ::todo-list])
   :query [:list-id]}
  (log/info "In TodoList render fn change10")

  (let [todos  (todos-list this {:list-id list-id})
        todos2 (reactive this [::todos-list {:list-id list-id}])]
    ;(def t' todos)
    (dom/div {}
      (dom/h1 "Todos")

      (dom/p "hi")
      (dom/button {:style {:padding 20 :margin "0 1rem"} :onClick #(add-random-todo! this)} "Add")

      (when (> (todos-total this {:list-id list-id}) 0)
        (dom/button {:style {:padding 20} :onClick #(rm-random-todo! this)} "Remove"))

      (ui-todos-total {:list-id list-id})
      (dom/pre (pr-str todos))
      (dom/hr)
      (map ui-todo todos))))

(def ui-todo-list (c/computed-factory TodoList))

(defsc Root [this {:root/keys [list-id]}]
  {:initial-state {:root/list-id :root/todos}
   :query         [:root/list-id :root/todos]}
  (dom/div {} (ui-todo-list {:list-id list-id})))

;; Fulcro app and init function
;=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defonce root (react-dom/createRoot (js/document.getElementById "app")))
(defonce fulcro-app
  (with-reactive-subscriptions (fulcro.app/fulcro-app {:render-root! (fn [component] (.render root component))})))

(comment (fulcro.app/current-state fulcro-app))

(defn ^:export init [] (fulcro.app/mount! fulcro-app Root js/app))

(defn ^:dev/after-load refresh []
  (subs/clear-subscription-cache! fulcro-app)
  (fulcro.app/force-root-render! fulcro-app)
  ;(fulcro.app/unmount! fulcro-app)
  ;(fulcro.app/mount! fulcro-app Root js/app {:initialize-state? false})
  )

;; todo:
;; add input form instead of merge-comp in the repl
;; add mutation

(defsc Todo2 [this props]
  {:query [:todo/id :todo/text :todo/state :todo/completed-at]
   :ident :todo/id})

(def todolist (f.eql/nc {:query [{:todo-list/todos (c/get-query Todo2)} :todo-list/id]
                         :ident :todo-list/id
                         :name  ::TODOList2}))

(def todo-sub (f.eql/create-component-subs Todo {}))
(def todo2-sub (f.eql/create-component-subs Todo2 {}))
(def todolist-sub (f.eql/create-component-subs todolist {:todo-list/todos todo2-sub}))
(def todo-list-id #uuid"042dcb63-ee9b-4bfc-a64b-50ce55bc720d")
(defn make-todo-list [id app]
  {:todo-list/id id
   :todo-list/todos (:root/todos (fulcro.app/current-state app)) })

(defn db->tree
  [c ident fulcro-app]
  (fdn/db->tree (if (map? c) c (c/get-query c)) ident (fulcro.app/current-state fulcro-app)))

(set! *print-namespace-maps* false)
(comment
  (make-todo-list todo-list-id fulcro-app)
  (swap! (::fulcro.app/state-atom fulcro-app) assoc-in [:todo-list/id todo-list-id] (make-todo-list todo-list-id fulcro-app))

  (db->tree todolist [:todo-list/id todo-list-id] fulcro-app)

  ;; todolist bench
  ;=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-
  (fdn/db->tree (c/get-query todolist) [:todo-list/id todo-list-id] (fulcro.app/current-state fulcro-app))
  (time (fdn/db->tree (c/get-query todolist) [:todo-list/id todo-list-id] (fulcro.app/current-state fulcro-app)))
  (simple-benchmark [q (c/get-query todolist)
                     args [:todo-list/id todo-list-id]
                     s (fulcro.app/current-state fulcro-app)]
    (fdn/db->tree q args s)
    100)
  (simple-benchmark [sub-args {:todo-list/id todo-list-id f.eql/query-key (c/get-query todolist)}]
    (todolist-sub fulcro-app sub-args) 100)
  (let [x (transient [])
        s (count x)
        ]
    (reduce))
  (time (todolist-sub fulcro-app {:todo-list/id todo-list-id f.eql/query-key (c/get-query todolist)}))


  (todolist-sub fulcro-app {:todo-list/id todo-list-id f.eql/query-key (c/get-query todolist)})
  (simple-benchmark [sub-args {:todo-list/id todo-list-id f.eql/query-key (c/get-query todolist)}] (todolist-sub fulcro-app sub-args) 100)


  ;; todo for component sub functions you should assert the first arg is a fulcro app?

  (todo2-sub fulcro-app {:todo/id #uuid"61e38c15-30d3-48fc-bc73-ab30c5b6ae78"})
  (todolist-sub fulcro-app {:root/list-id #uuid"61e38c15-30d3-48fc-bc73-ab30c5b6ae78"})

  (fdn/db->tree (c/get-query Todo) [:todo/id #uuid"61e38c15-30d3-48fc-bc73-ab30c5b6ae78"]
    (fulcro.app/current-state fulcro-app))

  (let [todo (first (get-in (fulcro.app/current-state fulcro-app) [:root/todos]))]
    (apply hash-map todo)
    )
  (simple-benchmark [q (c/get-query Todo)
                     todo (first (get-in (fulcro.app/current-state fulcro-app) [:root/todos]))
                     state (fulcro.app/current-state fulcro-app)
                     args (assoc (apply hash-map todo) f.eql/query-key q)]
    (todo-sub fulcro-app args)
    1000)

  (simple-benchmark [q (c/get-query Todo)
                     todo (first (get-in (fulcro.app/current-state fulcro-app) [:root/todos]))
                     state (fulcro.app/current-state fulcro-app)
                     args (assoc (apply hash-map todo) f.eql/query-key q)]
    (fdn/db->tree q todo state)
    1000)

  (todo2-sub
    fulcro-app
    {:todo/id        #uuid"61e38c15-30d3-48fc-bc73-ab30c5b6ae78"
     f.eql/query-key (c/get-query Todo)})
  (simple-benchmark [q (c/get-query Todo)
                     ident [:todo/id #uuid"61e38c15-30d3-48fc-bc73-ab30c5b6ae78"]
                     state (fulcro.app/current-state fulcro-app)
                     args {:todo/id        #uuid"61e38c15-30d3-48fc-bc73-ab30c5b6ae78"
                           f.eql/query-key q}]
    (todo-sub fulcro-app args)
    1000)
  (simple-benchmark [q (c/get-query Todo)
                     ident [:todo/id #uuid"61e38c15-30d3-48fc-bc73-ab30c5b6ae78"]
                     state (fulcro.app/current-state fulcro-app)]
    (fdn/db->tree q ident state)
    1000)

  (todo-sub fulcro-app {:todo/id #uuid"61e38c15-30d3-48fc-bc73-ab30c5b6ae78"})
  (fulcro.app/current-state fulcro-app)
  ;(simple-benchmark [])
  )
