# Use subscriptions with Fulcro

This document assumes knowledge of re-frame, especially subscriptions.

If you do not currently have that knowledge, please take the time to acquire it.

See the readme for references to learn more.

# Why?

Bring reactive UI rendering to fulcro.

Fulcro provides powerful features and abstractions but its rendering model makes dealing with derived data difficult 
to work with. Combined with the realization that a UI is mostly rendering derived data I didn't see a tractable way forward to 
continue using fulcro without a solution to this problem.

The way I think about this is that most UI libraries have a "pull" mental model where a component (the leaf) asks
for data it needs to render. Whereas in fulcro the model is "push" - a mutation augments the data and then tells the leaves to redraw,
pushing the data from the core out to the leaves.

The pull model is well studied and understood. It is the domain of dataflow programming and functional reactive programming.
You start with a core of data and perform pure functional transforms on this core to get the data in a shape needed to render.

The point of this integration is to bring the pull model to fulcro applications and solve the problem of rendering derived data. 
Making it simple (not interwoven) and easy (close at hand) to ensure what is rendered onscreen always reflects the state
in the fulcro app db without the user having to tell fulcro which components should re-render.

The other pain point it solves is the ability to query for this derived data in event handler code or other non-drawing code 
and to not need to store the derived data by the user, the library handles that.

In bullet points:

- Normalized graph db in the client is the correct model for UI state
  - because updates are: assoc-in/update-in [table id attr] 
  - if it isn't in this shape you now have a data synchronization problem
- We want derived data to only be (re)computed when needed to draw that data
- We don't want to do the bookkeeping of this derived data - it does not belong in view rendering paths.
  - If you store the derived data in app-db you no longer have a normalized source of truth. 
  - we may want to make use of this derived data elsewhere (e.g. event handlers)
  - we may forget all the circuitous paths that may update the downstream sources of the derived data, ending up with stale 
    derived data
  - we may forget which components need to redraw derived data after normalized data is updated
- There are quite a few large re-frame applications in production at this point and this model scales well both to large 
  codebases and to projects with numerous disparate contributors.
  - It removes decision making about when/where to compute derived data (it always goes in a subscription)
  - Views are only about rendering data, not transforming it.
  - The presence of these large re-frame applications shows that solving the DB problem by the developers (as re-frame does not
    have opinions about the shape of your app-db - no normalization by default) is tractable, whereas the author believes pushing the 
    responsibility of figuring out derived data to the application author does not scale, especially when you add more devs.
- One of the early and exciting selling points of Om/Fulcro was targeted refresh of only the components whose data has changed.
  There has recently been a move away from this render optimization because it is so hard to make sure all the components
  onscreen are redrawn that need to be. This library makes targeted refresh tractable (well really reagent does that and Mike Thompson's
  incredible discovery/invention of subscriptions).

# How?

Using render middleware that renders each fulcro component in a reagent Reaction using `reagent.ratom/run-in-reaction` 
When any data the render function is subscribed to (any ratom/reactions it derefed during render change)
a callback fires that will re-render the component.
When a component unmounts we remove the listener created by `run-in-reaction`.

Nothing else about using fulcro components has changed, they will still re-render following the usual fulcro usage.

Example:

## Use with fulcro class components

```clojure 
(defonce fulcro-app (subs/with-reactive-subscriptions (fulcro.app/fulcro-app {})))

(defregsub list-idents (fn [db {:keys [list-id]}] (get db list-id)))

;; anytime you have a list of idents in fulcro the subscription pattern is to
;; have input signals that subscribe to layer 2 subscriptions

(reg-sub ::todo-table :-> (fn [db] (-> db :todo/id)))

;; now any subscriptions that use ::todo-table as an input signal will only update if todo-table's output changes.

(defregsub todos-list :<- [::list-idents] :<- [::todo-table]
  (fn [[idents table]]
    (mapv #(get table (second %)) idents)))

(defregsub todos-total :<- [::todos-list] :-> count)

(defsc Todo [this {:todo/keys [text state]}]
  {:query         [:todo/id :todo/text :todo/state]
   :ident         :todo/id
   :initial-state (fn [text] (make-todo (or text "")))}
  (dom/div {}
    (dom/div "Todo:" (dom/div text))
    (dom/div "status: " (pr-str state))))

(def ui-todo (c/computed-factory Todo {:keyfn :todo/id}))

(defsc TodosTotal [this {:keys [list-id]}] {}
  (dom/h3 "Total todos: " (todos-total this {:list-id list-id})))

(def ui-todos-total (c/factory TodosTotal))

(defn add-random-todo! [app]
  (merge/merge-component! (c/any->app app) Todo (make-todo (str "todo-" (rand-int 1000))) :append [:root/todos]))
  
(defsc TodoList [this {:keys [list-id]}]
  {:ident (fn [] [:component/id ::todo-list])
   :query [:list-id]}
  (let [todos (todos-list this {:list-id list-id})]
    (dom/div {}
      (dom/button {:style {:padding 20 :margin "0 1rem"} :onClick #(add-random-todo! this)} "Add")
      (when (> (todos-total this {:list-id list-id}) 0)
        (dom/button {:style {:padding 20} :onClick #(rm-random-todo! this)} "Remove"))
      (ui-todos-total {:list-id list-id})
      (map ui-todo todos))))

(def ui-todo-list (c/computed-factory TodoList))

(defsc Root [this {:root/keys [list-id]}]
  {:initial-state {:root/list-id :root/todos}
   :query         [:root/list-id]}
  (ui-todo-list {:list-id list-id}))
  
(fulcro.app/mount! fulcro-app Root js/app)
```

the `subs/with-reactive-subscriptions` call assoc'es the ::fulcro.app/state-atom to be a reagent.ratom/atom. 
It also assigns the rendering algorithm used by the app to use the ident-optimized render. 
The intention of this library is that all derived data is computed using subscriptions
and rendered with their values - this way there are never any stale components on screen - just like in re-frame.

## Use with fulcro hooks components

See the `space.matterandvoid.subscriptions.react-hook-fulcro` namespace

# Subscription authoring tips

All the subscription compute functions are memoized and thus you must be aware that if the inputs to a subscription do not
change then the computation will return the cached data.

When integrating with fulcro the following strategy is recommended to deal with the design tension of wanting optimal caching
while still ensuring the UI reflects the most up-to-date state of the app db.

At the layer 2 level use db->tree and depend on the db - this way you'll ensure your downstream subs will properly
invalidate the memoization cache (or really, compute on new inputs).

Then in layer 3 subs you can select only the parts of the data tree from the layer 2 inputs that you care about, and
pass those to another layer 3 sub - this way the leaf subscription which the UI will use to render - can be memoized while
the layer 2 subs (by using db->tree) will ensure they pick up the newest dependent data from a fulcro query.

The short way to say it is if your layer-3 subscription takes as input an ident then you will likely get bit by the subscription
not updating properly.

So:
- never take an ident as input to a subscription (or a collection of them) - use db->tree instead
- add 'extractor' subscriptions on top of the db->tree ones to gain the benefits of memoization
    - for example if you are rendering a list of items but only their `::title` field and some other field changes on them,
      having a subscription that maps over them only pulling out the `::title` will enable the subscription to be cached.
- do not use the fulcro application from within a subscription to extract some data "side-band" because this is really
  hiding a subscription dependency - so refactor it to be another subscription, this way your compute graph will recompute
  correctly when dependent data changes.

## Do not use swap!

In short: always mutate the fulcro state atom as usual: you must use `transact!` to have your UI stay up to date.

For the best integration the fulcro rule applies: pretend there is no watch on the fulcro state atom.

You're going to have a bad time if you try to use `swap!` because fulcro caches a component's props on the component instance
so if a parent component uses a subscription but the child does not and instead renders its props - its parent will refresh 
via the reaction firing, but the leaf/child will not because fulcro is rendering its cached props.

# Future ideas

Integrating with fulcro-inspect - probably by adding instrumentation inside of defregsub that happens based on a compiler
flag, as well as during re-render - to allow inspecting how long subscriptions took to compute as well as which components
they caused to be re-rendered.
