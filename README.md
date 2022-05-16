This library extracts the subscriptions half of re-frame into a stand-alone library with the key difference that 
the data source is (app-db reactive atom in re-frame) an explicit argument to subscriptions.

This unlocks the utility of these for some creative integrations between changing out the data str

This unlocks both ends of the subscriptions chain to be free variables - you can use any backing source of data - like datascript
or a javascript object (maybe from a third party integration), it's up to you!
- and the UI layer - there is one simple integration point for using a UI library - you guessed it - anything that can
- return a react element.


both ends of the spectrum - new data structures/stores backing the core ratom as well as plugging in different rendering
integrations to display the data reactively.

This is an extraction of re-frame subscriptions into its own library, where the db (as a reagent.ratom/atom) is always
passed explicitly to `(subscribe)` calls instead of accessed via a global Var.

The original motivation was to use subscriptions with fulcro, but the library can be used with any data source that is 
wrapped in a `reagent.ratom/atom`.

In fact that is the library's only dependency from reagent, the `reagent.ratom` namespace. 
The UI integrations are added on top of this core.
is on the data at its core - this makes integrating simple.

library only has a dependency on the `reagent.ratom` namespace from the reagent codebase.

Subscriptions are a way to apply pure functions over a core data source to arrive at derived data from that source.

The difference from just using function composition is that the layers are cached, and having the ability to execute code 
in response to any of these values changing over time.

# Integrations

There are two API entry namespaces (for now) - one for use with fulcro and one for general use with any datasource.

See docs/fulcro.md for details on usage with fulcro.

The reg-sub API is the same as in re-frame (the subscription handlers are stored in a global var, but this can be easily
changed if you desire, and then the API becomes: `(reg-sub your-registry-value :hello (fn [db] (:hello db)))`)

The difference from upstream re-frame is when you invoke `(subscribe)` you pass in the root of the subscription directed acyclic graph: 
```clojure
(subscribe (reagent.ratom/atom {:hello 200}) [:hello])
```

# Examples

See the `examples` directory in the repo and shadow-cljs.edn for the builds if you clone the repo you 
can run them locally.

## Use with fulcro class components

## Use with a hashmap

## Use with datascript

```clojure 
(def schema {:todo/id {:db/unique :db.unique/identity}})
(defonce conn (d/create-conn schema))
(defonce dscript-db_ (ratom/atom (d/db conn)))

(defn make-todo [id text] {:todo/id id :todo/text text})
(def todo1 (make-todo #uuid"6848eac7-245c-4c5c-b932-8525279d4f0a" "todo1"))
(def todo2 (make-todo #uuid"b13319dd-3200-40ec-b8ba-559e404f9aa5" "todo2"))

;; This is the main thing to notice - by changing the ratom, any views subscribing to 
;; the data will upate

(defn transact! [conn data]
  (d/transact! conn data)
  (reset! dscript-db_ (d/db conn)))
  
(transact! conn [todo1 todo2])

(defsub all-todos 
  :-> (fn [db] (d/q '[:find [(pull ?e [*]) ...] :where [?e :todo/id]] db)))

(sut/defsub sorted-todos :<- [::all-todos] :-> (partial sort-by :todo/text))
(sut/defsub rev-sorted-todos :<- [::sorted-todos] :-> reverse)
(sut/defsub sum-lists :<- [::all-todos] :<- [::rev-sorted-todos] :-> (partial mapv count))

;; if you were to use these inside a reagent view the view will re-render when the data changes.
(all-todos dscript-db_)
(sorted-todos dscript-db_)
(rev-sorted-todos dscript-db_)

;; use the transact helper to ensure the ratom is updated as well as the db

(transact! conn [(make-todo (random-uuid) "another todo")])
```

# Differences/modifications from upstream re-frame

Details below, but the two big differences are:

1. The input signal function and the compute function are only passed your db and one hashmap as arguments - not the subscription vector.
2. The reagent Reaction that backs a subscription computatoin function is only cached in a reactive context, and the 
   computation function itself is memoized with a bounded cache.

## Only one map for all queries

All subscriptions are forced to receive only one argument: a hashmap.

On a team with lots of people working on one codebase if you remove points where decisions have to be made, especially when
they are arbitrary decisions (like how do we pass arguments to subscriptions) - then you get uniformity/compatibility for free 
and your codebase remains cohesive (at least in the domain of subscriptions in this case).

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
(subscribe [:subscription-keyword {:args 'map :here true}])
;or with no args:
(subscribe [:subscription-keyword])
```

Doing this will throw an exception:
```clojure
(subscribe [::my-sub {:arg1 5} 'any-other :other "args"])
;; or this
(subscribe [::my-sub "anything that is not a hashmap"])
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

If static arguments are declared on the subscription and args are also passed to the subscrpition they are merged with the user
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
(reg-sub ::item-ids #(get %1 (:list-id %2)))

;; DAN : todo - this example is not correct do not use <sub  in inputs fn
(reg-sub ::todos-list
  (fn [ratom {:keys [list-id]}] ;; <-- here we don't need useless sequential destructuring.
    (let [todo-ids (subs/<sub ratom [::item-ids {:list-id list-id}])]
      (mapv (fn [id] (subs/subscribe ratom [::todo {:todo/id id}])) todo-ids)))
  identity)

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
(subs/set-memoize! memoize-fn)
```
Now any subsequent calls to `reg-sub` will have their computation functions wrapped in the memoization function specified
which is `memoize-fn` in this example. 

Thus if you want to disable the memoization cache you can:
```clojure
(subs/set-memoize! identity)
```

Or if you want to change to your own caching policy/implementation you can do so.

It also means you can cache some subscriptions and not others by changing the function before subsequent `reg-sub` calls.


## `defsub` macro

There is a tiny macro in this library which in addition to registering a subscription also creates a `defn` with the provided name.
When this function is invoked it subscribes and derefs: `(deref (subscribe [::subscription-here arg]))`

This allows for better editor integration such as jump-to-definition support as well as searching for the use of the
subscription across a codebase.

You could also use your own defsub macro to add instrumentation, for example, around subscriptions.

# Implementation details

The codebase is quite tiny (the subs namespace, pretty much the same as in re-frame), but if you haven't played with 
reagent Reactions or Ratoms before it can be hard to follow. I found that playing with simple examples helped me to reason
about what is going on.


Subscriptions are implemented as reagent Reaction javascript objects and using one helper function `run-in-reaction`.
Reactions have a current value like a clojurescript atom, but they also have the characteristic that when their function body
executes if they deref any ratoms or reactions then the reaction will remember these in a list of watches, and whenever 
any of those dependent data sources changes, the function body will run again.

```clojure
(def base-data (ratom/atom 0))
(def sub1 (ratom/make-reaction (fn [] (.log js/console "sub1") (inc @base-data))))
(def sub2 (ratom/make-reaction (fn [] (.log js/console "sub2") (inc @sub1))))
(def sub3 (ratom/make-reaction (fn [] (.log js/console "sub3") (inc @sub2))))

(deref sub1)
(deref sub2)
(deref sub3)
(swap! base-data inc)
```
even though the base-data was swap!'d there is no reactivity yet.
To get that we use the reagent helper function `run-in-reaction`, which lets us specify one function to run right now
and again using a reaction will track any dependent reactions. The next piece is that it lets you pass a callback to fire
when any dependent data fires. And this is where we render our views again.

I would not have been able to figure this out if the reagent component namespace didn't already exist demonstrating how 
to make this work in practice.

# Integrating this library with other view layers

In short use `reagent.ratom/run-in-reaction`, see the reagent source for inspiration:

https://github.com/reagent-project/reagent/blob/f64821ce2234098a837ac7e280969f98ab11342e/src/reagent/impl/component.cljs#L254

or the fulcro integration:

<todo>

It takes a `run` function callback which will be invoked when any ratom's or Reactions are deref'd in the main function 
pass to run-in-reaction. In this `run` function you perform the side-effecting re-render.

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

Open the shadow-cljs inspector page and open the page that hosts the tests.


