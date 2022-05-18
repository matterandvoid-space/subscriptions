This library extracts the subscriptions half of [re-frame](https://github.com/day8/re-frame) into a standalone library,
it also makes a few adjustments, the key one being that the data source is an explicit argument you pass to subscriptions.

This unlocks the utility of subscriptions for some creative integrations.

It allows both ends of the subscriptions chain to be free variables 
- you can use any backing source of data - like datascript or a javascript object (maybe from a third party integration), it's up to you!
- the UI layer - there is one simple integration point for rendering with any react cljs rendering library.

The original motivation was to use subscriptions with fulcro, but the library can be used with any data source that is 
wrapped in a `reagent.ratom/atom`.

In fact that is the library's only dependency from reagent, the `reagent.ratom` namespace. 
The UI integrations are added on top of this core.

If you haven't used re-frame, subscriptions are a way to apply pure functions over a core data source to arrive at derived data from that source.
They also allow "subscribing" to a piece of derived data - that is, specifying a callback function to be invoked when the data changes,
with the intention of committing effects - changing the state of the world - in this library that is usually affecting 
the state of pixels on a display attached to a computer.

The difference from just using function composition is that the layers are cached, and that you can execute code 
in response to any of these values changing over time.

One other introductory note: The API in this codebase may have breaking changes in the future if new patterns emerge from actual usage.
This seems unlikely, but I'm putting this warning here to allow for mutative/non-accretive changes to the codebase if they
are warranted, during the early stages of use.

# Integrations

There are two API entry namespaces (for now) - one for use with fulcro `space.matterandvoid.subscriptions.fulcro` 
and one for general use with any datasource, `space.matterandvoid.subscriptions.core`

To avoid dependency conflicts this library does not declare a dependency on fulcro or on reagent. Please add the version
of these libraries you would like to your own project.

See docs/fulcro.md for details on usage with fulcro.

The reg-sub API is the same as in re-frame 
_aside_: the subscription handlers are stored in a global var, but this can be easily changed if you desire, and then the API becomes:
```clojure
(reg-sub your-registry-value :hello (fn [db] (:hello db)))
```

The difference from upstream re-frame is when you invoke `(subscribe)` you pass in the root of the subscription graph: 
```clojure
(subscribe (reagent.ratom/atom {:hello 200}) [:hello])
```

# Examples

There are working examples in this repo in the `examples` directory. See `shadow-cljs.edn` for the build names. 
You can clone the repo and run them locally.

## Use with fulcro class components

```clojure 
(defonce fulcro-app (subs/with-subscriptions (fulcro.app/fulcro-app {})))

(defsub list-idents (fn [db {:keys [list-id]}] (get db list-id)))

;; anytime you have a list of idents in fulcro the subscription pattern is to
;; have input signals that subscribe to layer 2 subscriptions

(reg-sub ::todo-table :-> (fn [db] (-> db :todo/id)))

;; now any subscriptions that use ::todo-table as an input signal will only update if todo-table's output changes.

(defsub todos-list :<- [::list-idents] :<- [::todo-table]
  (fn [[idents table]]
    (mapv #(get table (second %)) idents)))

(defsub todos-total :<- [::todos-list] :-> count)

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

## Use with a hashmap

```clojure 
(defonce db_ (ratom/atom {}))

(defn make-todo [id text] {:todo/id id :todo/text text})
(def todo1 (make-todo #uuid"6848eac7-245c-4c5c-b932-8525279d4f0a" "todo1"))
(def todo2 (make-todo #uuid"b13319dd-3200-40ec-b8ba-559e404f9aa5" "todo2"))
(swap! db_ assoc :todos [todo1 todo2])

(defsub all-todos :-> :todos)
(defsub sorted-todos :<- [::all-todos] :-> (partial sort-by :todo/text))
(defsub rev-sorted-todos :<- [::sorted-todos] :-> reverse)
(defsub sum-lists :<- [::all-todos] :<- [::rev-sorted-todos] :-> (partial mapv count))

;; if you were to use these inside a reagent view the view will re-render when the data changes.
(all-todos db_)
(sorted-todos db_)
(rev-sorted-todos db_)

(swap! db_ update :todos conj (make-todo (random-uuid) "another todo"))
```

## Use with Datascript

```clojure 
(def schema {:todo/id {:db/unique :db.unique/identity}})
(defonce conn (d/create-conn schema))
(defonce dscript-db_ (ratom/atom (d/db conn)))

(defn make-todo [id text] {:todo/id id :todo/text text})
(def todo1 (make-todo #uuid"6848eac7-245c-4c5c-b932-8525279d4f0a" "todo1"))
(def todo2 (make-todo #uuid"b13319dd-3200-40ec-b8ba-559e404f9aa5" "todo2"))

;; This is the main thing to notice - by changing the ratom, any views subscribing to 
;; the data will update

(defn transact! [conn data]
  (d/transact! conn data)
  (reset! dscript-db_ (d/db conn)))
  
(transact! conn [todo1 todo2])

(defsub all-todos 
  :-> (fn [db] (d/q '[:find [(pull ?e [*]) ...] :where [?e :todo/id]] db)))

(defsub sorted-todos :<- [::all-todos] :-> (partial sort-by :todo/text))
(defsub rev-sorted-todos :<- [::sorted-todos] :-> reverse)
(defsub sum-lists :<- [::all-todos] :<- [::rev-sorted-todos] :-> (partial mapv count))

;; if you were to use these inside a reagent view the view will re-render when the data changes.
(all-todos dscript-db_)
(sorted-todos dscript-db_)
(rev-sorted-todos dscript-db_)

;; use the transact helper to ensure the ratom is updated as well as the db

(transact! conn [(make-todo (random-uuid) "another todo")])
```

# Differences/modifications from upstream re-frame

Details below, but the three big differences are:

1. The input signal function is only passed two arguments: your ratom and a single hashmap of arguments.
   The compute function is only passed your db and one hashmap as arguments.
   Niether gets passed the vector which was passed to `subscribe`.
2. `subscribe` calls must be invoked with the base data source and optionally one argument which must be a hashmap.
3. The reagent Reaction that backs a subscription computation function is only cached in a reactive context, and the 
   computation function itself is memoized with a bounded cache, making subscriptions safe to use in any context.

And one smaller difference:

This is a ClojureScript library, no attempt is made to support execution in clojure.

## Only one map for all queries

All subscriptions are forced to receive only one argument: a hashmap.

On a team with lots of people working on one codebase if you remove points where decisions have to be made, especially when
they are arbitrary decisions (like how do we pass arguments to subscriptions) - then you get uniformity/compatibility for free 
and your codebase remains cohesive (at least in the domain of subscriptions in this case).
This also allows for interesting dynamic use-cases as the hashmap can be easily manipulated across an entire codebase.

I've had to deal with a large re-frame application where subscriptions were parameterized by components, and having to
take into account all parameter passing styles is a pain and can lead to subtle bugs when combining parameters across a codebase 
and doing so dynamically.

Taking a tip from many successful clojure projects which are able to be extended and grown and integrated over time
(e.g. fulco, pathom, pedestal, malli), this library forces all subscriptions to take at most one argument which must be a hashmap.

Some of the benefits are:

- All arguments are forced to be named, aiding readability
- You can easily flow data through the system, like you might want to do when creating subscription utilities used across 
  components in your application, like having components that can be parameterized with subscriptions as well as parameterizing
  the arguments to those subscriptions.
- This in turn encourages the use of fully qualified keywords
- Which in turn makes using malli or schema or spec to validate the arguments much simpler (e.g. having a registry of keyword to schema/spec).

This format of a 2-tuple with a tag as the first element and data as the second shows up in lots of places, here is 
a great talk about modeling information this way by Jeanine Adkisson from the 2014 Conj:

[Jeanine Adkisson - Variants are Not Unions](https://www.youtube.com/watch?v=ZQkIWWTygio)

Concretely, all subscribe calls must have this shape:

```clojure
(subscribe data-source [:subscription-keyword {:args 'map :here true}])
;or with no args:
(subscribe data-source [:subscription-keyword])
```

Doing this will throw an exception:
```clojure
(subscribe data-source [::my-sub {:arg1 5} 'any-other :other "args"])
;; or this
(subscribe data-source [::my-sub "anything that is not a hashmap"])
```

Another nice benefit from adopting this policy is that we can then flow through the args to all input signals using 
the `:<-` input syntax, for example:

```clojure
(defonce base-db (reagent.ratom/atom {:num-one 500 :num-two 5}))
(defsub first-sub (fn [db {:keys [kw]}] (kw db)))
(defsub second-sub :<- [::first-sub] :-> #(+ 100 %))
(defsub third-sub :<- [::first-sub] :<- [::second-sub] :-> #(reduce + %))

(third-sub base-db {:kw :num-one}) ; => 1100
(third-sub base-db {:kw :num-two}) ; => 110
```

If static arguments are declared on the input signals and args are also passed to the subscrpition they are merged with the user
specified value overriding the static ones - as in: `(merge static-args user-args)`

```clojure
(defonce base-db (reagent.ratom/atom {:num-one 500 :num-two 5}))
(defsub first-sub (fn [db {:keys [kw]}] (kw db)))
(defsub second-sub :<- [::first-sub] :-> #(+ 100 %))
(defsub third-sub :<- [::first-sub {:kw :num-two}] :<- [::second-sub {:kw :num-two}] :-> #(reduce + %))

(third-sub base-db {:kw :num-one}) ; => 1100
(third-sub base-db {:kw :num-two}) ; => 110
(third-sub base-db) ; => 110
;; but invoking a subscription with no "default" parameters will throw in this case (kw will be null in first-sub):
(second-sub base-db) ; =>  Cannot read properties of null (reading 'call')
```

Right now `merge` is used, but this function can be swapped out if you wish:

```clojure
(subs/set-args-merge-fn! your-lib/deep-merge)
```
This will affect all subsequent calls to `reg-sub`.

## Subscription keyword is never passed to any callbacks

I'm sure you may notice if you've used re-frame before that the query id is never used in actual code - neither to 
produce the input signals, or in the computation function.

This library removes another point where a decision has to be made about how the callbacks will be called - they are 
always passed the source of your data (usually a ratom for inputs fn, and the db value for compute fn) and the query args.

Here's an example where we query for a list of todos, where the data is normalized

```clojure
(defonce db_ 
  (reageent.ratom/atom
    {:list-one [#uuid"c906f43e-b91d-464d-88cb-0c54988ee847" #uuid"62864412-d146-4111-b339-8fb3f5f5d236"]
     :todo/id {#uuid"c906f43e-b91d-464d-88cb-0c54988ee847" #:todo{:id #uuid"c906f43e-b91d-464d-88cb-0c54988ee847",
                                                                  :text "todo1", :state :incomplete},
               #uuid"62864412-d146-4111-b339-8fb3f5f5d236" #:todo{:id #uuid"62864412-d146-4111-b339-8fb3f5f5d236",
                                                                  :text "todo2", :state :incomplete},
               #uuid"f4aa3501-0922-47a5-8579-70a4f3b1398b" #:todo{:id #uuid"f4aa3501-0922-47a5-8579-70a4f3b1398b",
                                                                  :text "todo3", :state :incomplete}}}))
(reg-sub ::item-ids #(get %1 (:list-id %2))) ;; <- here the second arg is a hashmap
(reg-sub ::todo-table :-> :todo/id)

(defsub todos-list :<- [::item-ids] :<- [::todo-table]
  (fn [[ids table]]
    (mapv #(get table %) ids)))

(subs/<sub db_ [::todos-list {:list-id :list-one}])
```
And the same thing for layer 2:

```clojure
(reg-sub ::todo-text 
  (fn [db {:todo/keys [id]}]  ;; <-- for layer 2 you are just passed the args
    (get-in db [:todo/id id :todo/text])))

(subs/<sub db_ [::todo-text {:todo-id #uuid"f4aa3501-0922-47a5-8579-70a4f3b1398b"}])
```

If you _really_ need the query id you can just assoc it onto the args map. One less thing to worry about.

This style also means there is no need for the `:=>` syntax sugar (but `:->` is still useful for functions that only need
to operate on the db or the single computed value).

## Memoized subscription computation functions.

The underlying reagent.ratom/Reaction used in re-frame is cached - this library also does this.

The issue is that this leads to memory leaks if you attempt to invoke a subscription outside of the reactive propagation stage.

That is, the reaction cache is used for example in a re-frame web app when a `reset!` is called on the reagent.ratom/atom
app-db - this triggers reagent code that will re-render views, it is during this stage that the subscription computation function
runs and the reaction cache is successfully used.

The key part is that reagent adds an on-dispose handler for the reaction which is invoked when a component unmounts.

Thus, if you try to use a subscription outside of the reactive context (and that is never used by a currently mounted component)
the subscription's reaction will be cached but never disposed, consuming memory that is never relinquished until the application is restarted.

This library incorporates two changes to make sure there are no memory leaks and yet that subscriptions can be used in any context
while still being cached.

The changes are:

- do not cache reactions if we are not in a reactive context (reagent indicates a reactive context by binding a dynamic variable.)
- memoize all subscription computation functions with a bounded cache that evicts the least recently used subscription when full.

This is possible because subscriptions are pure functions and the layer 2 accessor subscriptions will invalidate for new 
data when a new value for app-db is `reset!`.

As long as you follow the rules/intended design of using subscriptions this will not matter to you - the rule is 
you can only compute on the inputs specified by the subscription mechanisms - if your functions are not pure you 
will have a bad time (you will see stale values).


The function used for memoization can be changed via this helper function:

```clojure
(subs/set-memoize-fn! memoize-fn)
```
Now any subsequent calls to `reg-sub` will have their computation functions wrapped in the memoization function specified
which is `memoize-fn` in this example. 

Thus if you want to disable the memoization cache you can:
```clojure
(subs/set-memoize-fn! identity)
```

Or if you want to change to your own caching policy/implementation you can do so.

It also means you can cache some subscriptions and not others by changing the function before subsequent `reg-sub` calls.

## `defsub` macro

There is a tiny macro in this library which in addition to registering a subscription also outputs a `defn` with the provided name.
When this function is invoked it subscribes and derefs the subscription while passing along any arguments.

Here is an example:

```clojure
(defsub sorted-todos :<- [::all-todos] :-> (partial sort-by :todo/text))
;; expands to:
(do
  (reg-sub ::sorted-todos  :<- [::all-todos] :-> (partial sort-by :todo/text))
  
  (defn sorted-todos 
    ([ratom] (deref (subs/subscribe ratom [::sorted-todos])))
    ([ratom args] (deref (subs/subscribe ratom [::sorted-todos args])))))
```

This allows for better editor integration such as jump-to-definition support as well as searching for the use of the
subscription across a codebase.

Also, because the subscriptions are memory-safe to use in a non-reactive context they are really just functions, how they're implemented
is just a detail.

You could also use your own defsub macro to, for example, instrument the calls to subscriptions or manipulate the args map 
for all subscriptions.

# Implementation details

The codebase is quite tiny (the impl.subs namespace, pretty much the same as in re-frame), but if you haven't played with 
reagent Reactions or Ratoms before it can be hard to follow. I found that playing with simple examples helped me to reason
about what is going on.

Subscriptions are implemented as reagent Reaction javascript objects (`deftype` in cljs) and using one helper function `run-in-reaction`.
Reactions have a current value like a clojurescript atom, but they also have an attached function (the `f` member on the type)
the characteristic that when this function body executes if it deref's any ratoms or reactions then the reaction will 
remember these in a list of watches. This list of watches is what run-in-reaction uses to fire another callback in response
to any depedent reactions/ratoms updating. - See the reagent.ratom namespace, especially `deref-capture`, `in-context`, and `notify-deref-watcher!`.

The implementation of this in reagent is quite elegant - the communication is done via a javascript object as shared memory
(the reaction or ratom) between the call stack using a dynamic variable.

```clojure
(def base-data (ratom/atom 0))
(def sub1 (ratom/make-reaction (fn [] (.log js/console "sub1") (inc @base-data))))
(def sub2 (ratom/make-reaction (fn [] (.log js/console "sub2") (inc @sub1))))
(def sub3 (ratom/make-reaction (fn [] (.log js/console "sub3") (inc @sub2))))

(def obj (js-obj))
(def r
  (ratom/run-in-reaction
    (fn []
      ;; any ratoms/reactions deref'd here will be "watched"
      
      ;; here I am deref'ing the base so we can see it react - re-frame subscriptions will do this via the chain
      ;; of input fns - these are deref'd by the leaf subscription
      @base-data
      (println "the sub3 is: " @sub3))
    obj "reaction-key"
    (fn react []
      ;; here is our effect:
      (println "Reacted!" @sub3))
    ;; I honestly don't know what this does exactly, but reagent passes this, then so do I :)
    {:no-cache true}))
(swap! base-data inc)
```
To get reactivity we use the reagent helper function `run-in-reaction`, which lets us specify one function to run right now
and one to run reactively. For UIs the reactive callback is where we redraw a component.

I would not have been able to figure this out if the reagent component namespace didn't already exist demonstrating how 
to make this work in practice - definitely refer to the source and play around at a repl to explore this.

# Integrating this library with other view layers

In short use `reagent.ratom/run-in-reaction`, see the reagent source for inspiration:

https://github.com/reagent-project/reagent/blob/f64821ce2234098a837ac7e280969f98ab11342e/src/reagent/impl/component.cljs#L254

It takes a `run` function callback which will be invoked when any ratom's or Reactions are deref'd in the main function 
passed to run-in-reaction. In this `run` function you perform the side-effecting re-render.

Hooks support might be different, you can consult the reagent sources for inspiration.

# References 

reagent:

https://github.com/reagent-project/reagent

re-frame:

https://day8.github.io/re-frame/re-frame

Mike Thompson on the history of re-frame:

https://player.fm/series/clojurestream-podcast/s4-e3-re-frame-with-mike-thompson

# Development and contributing

clone the repo and:

```bash
bb dev
```
Open the shadow-cljs builds page and then open the page that hosts the tests or an example app
