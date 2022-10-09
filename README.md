### Derived data flowing - from anywhere - to anywhere.

This library extracts the subscriptions half of [re-frame](https://github.com/day8/re-frame) into a standalone library,
it also makes a few adjustments, the key one being that the data source is an explicit argument you pass to subscriptions.

This unlocks the utility of subscriptions for some creative integrations allowing both ends of the subscriptions chain to be free variables.

The main features of this library are:

- Allow the use of any backing source of data - like a datascript db
- Attach arbitrary reactive logic to your subscriptions, meaning you can use any UI layer. There is one simple integration point for rendering with any react cljs rendering library.
- You can use functions that return reactions or cursors as subscriptions instead of just keywords (thanks to Danny Freeman for showing how simple this is to support: https://git.sr.ht/~dannyfreeman/repose)
- Clojure support - there is no reactivity but the computation chain works.
- Subscriptions that fulfill EQL queries for fulco-style components

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

# Usage / Integrations

Get the latest coordinates on clojars: 

[![Clojars Project](https://img.shields.io/clojars/v/space.matterandvoid/subscriptions.svg)](https://clojars.org/space.matterandvoid/subscriptions)

API Docs:

[![cljdoc badge](https://cljdoc.org/badge/space.matterandvoid/subscriptions)](https://cljdoc.org/d/space.matterandvoid/subscriptions)

_note_ this library depends on reagent, but given its prevalance and chance for version conflicts does not declare it 
as a dependency, you must add it to your deps.

Get the coordinates for the latest version of reagent here: https://clojars.org/reagent

This library also requires the following packages be installed from npm:
`react` `react-dom` and `use-sync-external-store`

```bash
npm install -D react react-dom use-sync-external-store
```

There are two API entry namespaces (for now) - one for use with Fulcro `space.matterandvoid.subscriptions.fulcro` 
and one for general use with any datasource, `space.matterandvoid.subscriptions.core`

To avoid dependency conflicts this library does not declare a dependency on Fulcro or on Reagent. Please add the version
of these libraries you would like to your own project.

See [docs/fulcro.md](docs/fulcro.md) for details on usage with Fulcro.

The reg-sub API is the same as in re-frame 
_aside_: the subscription handlers are stored in a global var, but this can be easily changed if you desire, and then the API becomes:
```clojure
(reg-sub your-registry-value :hello (fn [db] (:hello db)))
```

The difference from upstream re-frame is when you invoke `(subscribe)` you pass in the root of the subscription graph:
```clojure
(subscribe (reagent.ratom/atom {:hello 200}) [:hello])
```

# EQL queries support

Starting with version `2022.09.28` this library includes support for creating subscriptions that will fulfill queries based 
on Fulcro components. 

EQL is a specification for a query language that provides no semantics. In this way applications and libraries can use it by adding their 
own semantics. See the EQL git repository for more info: https://github.com/edn-query-language/eql

In addition to standard Datomic pull style EQL queries there is also support for handling to-many relationships 
- determining which nodes to pull dynamically, as well as using a recursive transformation function on the returned nodes.
This allows for declarative recursive query syntax which works for CLJS data sources as well as any Clojure database that
supports an `entity` API. Currently there is support for Datalevin, XTDB, and Fulcro hashmaps.

See the document [docs/eql_queries.md](docs/eql_queries.md) for more information and examples.

# Examples

There are working examples in this repo in the `examples` directory. See `shadow-cljs.edn` for the build names. 
You can clone the repo and run them locally.

## Use with a hashmap

```clojure 
(require [space.matterandvoid.subscriptions.core :refer [reg-sub <sub subscribe]])

(defonce db_ (ratom/atom {}))

(defn make-todo [id text] {:todo/id id :todo/text text})
(def todo1 (make-todo #uuid"6848eac7-245c-4c5c-b932-8525279d4f0a" "todo1"))
(def todo2 (make-todo #uuid"b13319dd-3200-40ec-b8ba-559e404f9aa5" "todo2"))
(swap! db_ assoc :todos [todo1 todo2])

(reg-sub ::all-todos :-> :todos)
(reg-sub ::sorted-todos :<- [::all-todos] :-> (partial sort-by :todo/text))
(reg-sub ::rev-sorted-todos :<- [::sorted-todos] :-> reverse)
(reg-sub ::sum-lists :<- [::all-todos] :<- [::rev-sorted-todos] :-> (partial mapv count))

;; if you were to use these inside a reagent view the view will re-render when the data changes.
(<sub db_ [::all-todos])
(<sub db_ [::sorted-todos])
(<sub db_ [::rev-sorted-todos])

(swap! db_ update :todos conj (make-todo (random-uuid) "another todo"))
```

## Use with Datascript

```clojure 
(require [space.matterandvoid.subscriptions.core :refer [reg-sub <sub subscribe]])
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

(reg-sub ::all-todos 
  :-> (fn [db] (d/q '[:find [(pull ?e [*]) ...] :where [?e :todo/id]] db)))

(reg-sub ::sorted-todos :<- [::all-todos] :-> (partial sort-by :todo/text))
(reg-sub ::rev-sorted-todos :<- [::sorted-todos] :-> reverse)
(reg-sub ::sum-lists :<- [::all-todos] :<- [::rev-sorted-todos] :-> (partial mapv count))

;; if you were to use these inside a reagent view the view will re-render when the data changes.
(<sub dscript-db_ [::all-todos])
(<sub dscript-db_ [::sorted-todos])
(<sub dscript-db_ [::rev-sorted-todos])

;; use the transact helper to ensure the ratom is updated as well as the db

(transact! conn [(make-todo (random-uuid) "another todo")])
```

I haven't used datascript much so there may be better/more efficient integrations, this is just one example.

## Use with React hooks

There are four react hooks in the `space.matterandvoid.subscriptions.react-hook` namespace 

- `use-sub`, which takes one subscription vector 
- `use-sub-map` which takes a hashmap of keywords to subscription vectors intended to be destructured.
- `use-reaction` which takes a Reagent Reaction, the output of the hook is the return value of the Reaction.
- `use-reaction-ref` which takes a React Ref which contains a Reagent Reaction, the output of the hook is the return value of the reaction.

The same hooks for fulcro use are in `space.matterandvoid.subscriptions.react-hook-fulcro`

With this implementation components will re-render once per animation frame (via requestAnimationFrame) even if the reactive
callback fires multiple times in one frame.

These hooks are all implemented via [useSyncExternalStore](https://beta.reactjs.org/apis/react/useSyncExternalStore) allowing
them to be used in React's concurrent rendering mode.

See the examples directory in the source for working code.

```clojure 
(require [space.matterandvoid.subscriptions.react-hook :refer [reg-sub use-sub use-sub-map use-reaction]])
(defonce db_ (ratom/atom {}))

(defn make-todo [id text] {:todo/id id :todo/text text})
(def todo1 (make-todo #uuid"6848eac7-245c-4c5c-b932-8525279d4f0a" "todo1"))
(def todo2 (make-todo #uuid"b13319dd-3200-40ec-b8ba-559e404f9aa5" "todo2"))
(swap! db_ assoc :todos [todo1 todo2])

(reg-sub ::all-todos :-> :todos)
(reg-sub ::sorted-todos :<- [::all-todos] :-> (partial sort-by :todo/text))

;; lifted from helix.core
(defn $ [type & args]
  (let [?p (first args), ?c (rest args), type' (cond-> type (keyword? type) name)]
    (if (map? ?p)
      (apply react/createElement type' (clj->js ?p) ?c)
      (apply react/createElement type' nil args))))

(defn a-react-hook-component []
  (let [{:keys [my-todos] :as the-subs} 
           (use-sub-map db_ {:my-todos [::all-todos] 
                             :sorted-todo-list [::sorted-todos]})
          
         ;; this is contrived, but you could imagine passing in subs as a prop
         ;; or other dynamic possibilities
         some-subs [[::sorted-todos] [:all-todos]]
         list-of-lists (use-reaction (make-reaction (fn [] (mapv <sub some-subs)))]
    ($ :div
      ($ :button #js{:onClick #(swap! db_ update :todos conj (make-todo (random-uuid) "another todo"))}
       "Add a todo")
      ($ :h4 "todos: " (pr-str my-todos))
      ($ :h4 "sorted todos: " (pr-str (:sorted-todo-list the-subs))))))
```

### Use React Context to pass the datasource through the component tree

The hooks `use-sub` and `use-sub-map` have single-arity versions which will look up the datasource using React [context](https://beta.reactjs.org/apis/react/createContext#creating-context).

A React context object is `def`'d in the library for you to bind a value to for a component tree.

Here is an example using [helix](https://github.com/lilactown/helix) for react rendering:

```clojure
(ns co.company.my-app
  (:require
    [space.matterandvoid.subscriptions.core :as subs :refer [defsub]]
    [space.matterandvoid.subscriptions.reagent-ratom :as ratom]
    [space.matterandvoid.subscriptions.react-hook :as subs.hooks]
    [helix.core :as hc]))

(def my-datasource (ratom/atom {:a-number 500}))

(defsub a-number :-> :a-number)

(hc/a-component []
  (let [the-number (subs.hooks/use-sub [a-number])] ; <-- notice here we don't have to pass the datasource
    (hc/$ :div "A number: " the-number)))

(hc/defnc app []
  (hc/provider {:context subs/datasource-context :value my-datasource}
    (hc/$ :div
      (hc/$ :p "your app here")
      (hc/$ a-component))))
```

# Differences/modifications from upstream re-frame

Details below, but the three big differences are:

1. The input signals function is only passed two arguments: your data source (usually a ratom) and a single hashmap of arguments.
   The compute function is only passed your db and one hashmap as arguments.
   Neither gets passed the vector which was passed to `subscribe`.
2. `subscribe` calls must be invoked with the base data source and optionally one argument which must be a hashmap.
3. The reagent Reaction that backs a subscription computation function is only cached in a reactive context, making subscriptions safe to use in any context.
4. Support for not using any global registry of subscriptions, instead you can subscribe directly to a function that returns a reagent Reaction/Ratom/Cursor 

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
(e.g. fulcro, pathom, pedestal, malli), this library forces all subscriptions to take at most one argument which must be a hashmap.

Some of the benefits are:

- All arguments are forced to be named, aiding readability
- You can easily flow data through the system, like you might want to do when creating subscription utilities used across 
  components in your application, having components that can be parameterized with subscriptions as well as parameterizing
  the arguments to those subscriptions.
- This in turn encourages the use of fully qualified keywords
- Which in turn makes using malli or schema or spec to validate the arguments much simpler (e.g. having a registry of keyword to schema/spec).

This format of a 2-tuple with a tag as the first element and data as the second shows up in lots of places 
(hiccup markup with no children, MapEntry), here is a great talk about modeling information this way by Jeanine Adkisson from the 2014 Conj:

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
(reg-sub ::first-sub (fn [db {:keys [kw]}] (kw db)))
(reg-sub ::second-sub :<- [::first-sub] :-> #(+ 100 %))
(reg-sub ::third-sub :<- [::first-sub] :<- [::second-sub] :-> #(reduce + %))

(<sub base-db [::third-sub {:kw :num-one}]) ; => 1100
(<sub base-db [::third-sub {:kw :num-two}]) ; => 110
```

If static arguments are declared on the input signals and args are also passed to the subscription at runtime the static 
args are merged with the user specified one - as in: `(merge static-args user-args)`

```clojure
(defonce base-db (reagent.ratom/atom {:num-one 500 :num-two 5}))
(reg-sub ::first-sub (fn [db {:keys [kw]}] (kw db)))
(reg-sub ::second-sub :<- [::first-sub] :-> #(+ 100 %))
(reg-sub ::third-sub :<- [::first-sub {:kw :num-two}] :<- [::second-sub {:kw :num-two}] :-> #(reduce + %))

(<sub base-db [::third-sub {:kw :num-one}]) ; => 1100
(<sub base-db [::third-sub {:kw :num-two}]) ; => 110
(<sub base-db [::third-sub]) ; => 110
;; but invoking a subscription with no "default" parameters will throw in this case (kw will be null in first-sub):
(<sub base-db [::second-sub]) ; =>  Cannot read properties of null (reading 'call')
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

(reg-sub ::todos-list :<- [::item-ids] :<- [::todo-table]
  (fn [[ids table]]
    (mapv #(get table %) ids)))

(subs/<sub db_ [::todos-list {:list-id :list-one}])

(reg-sub ::todo-text 
  (fn [db {:todo/keys [id]}]  ;; <-- just passed the args map
    (get-in db [:todo/id id :todo/text])))

(subs/<sub db_ [::todo-text {:todo/id #uuid"f4aa3501-0922-47a5-8579-70a4f3b1398b"}])
```

If you _really_ need the query id you can just assoc it onto the args map. One less thing to worry about.

This style also means there is no need for the `:=>` syntax sugar (but `:->` is still useful for functions that only need
to operate on the db or the single computed value).

## Using subscriptions in any context

The underlying reagent.ratom/Reaction used in re-frame is cached - this library also does this.

The issue is that this leads to memory leaks if you attempt to invoke a subscription outside of the reactive propagation stage.

That is, the reaction cache is used for example in a re-frame app when `reset!` is called on the reagent.ratom/atom
app-db - this triggers reagent code that will re-render views, it is during this stage that the subscription computation function
runs and the reaction cache is successfully used.

The key part is that reagent adds an on-dispose handler for the reaction which is invoked when a component unmounts.

Thus, if you try to use a subscription outside of the reactive context (and that is never used by a currently mounted component)
the subscription's reaction will be cached but never disposed, consuming memory that is never relinquished until the application is restarted.

This library does not use the subscription cache if a subscription is called outside a reactive context (reagent indicates a reactive context by binding a dynamic variable)
 and thus you can invoke your subscriptions in any context.

## Using functions as subscriptions

**This idea was inspired/taken from Danny Freeman's `repose` library: https://git.sr.ht/~dannyfreeman/repose**

Subscriptions are just chains of functions that return reagent Reactions/Ratoms/RCursors. In re-frame when you `reg-sub`
you are just associating a keyword (the subscription name) with one of these functions.

re-frame has a bit of parsing code to deal with the signals input syntax, but ultimately a subscription handler is a function
with this shape:

```clojure
(fn [data-source arguments]
  (reagent.ratom/make-reaction (fn []
                                 ; deref any dependent subscriptions (like your input signals)
                                 ; and return some computed value.
                                 )))
```
When you call `subscribe` the keyword in the subscription vector is used to lookup this "handler" function. So we can skip
that keyword step and just pass the handler itself, and `subscribe` can invoke that function directly.

That's what this library allows. These handler functions are the same as `reg-sub-raw` in re-frame, but again, without an
associated keyword.

Here is a brief example:

```clojure
(defonce db_ (ratom/atom {:a-number        5
                          :a-string        "hello"
                          :show-component? true
                          :level1          {:level2 {:level3 500}}
                          :another-num     100}))
```

Here is a "layer 2" subscription

```clojure 
(defn a-fn-sub [db_]
  (make-reaction (fn [] (:a-number @db_))))
```

You can also use cursors for "layer 2" subscriptions which will be more efficient than using 
Reactions, as Cursors compare equality using [`identical?`](https://github.com/reagent-project/reagent/blob/059b26d07ab6dd0145739a6d22bb334ca4f05c1a/src/reagent/ratom.cljs#L265)
versus Reactions which are first invoked and then compared using `=` on the output.

You have to use the provided `cursor` function in this library though, which adds support for cleaning it up from the subscription cache 
when it is not used in any components:
```clojure
(require '[space.matterandvoid.subscriptions.reagent-ratom :refer [cursor]])
(defn a-layer-2-fn [db_]
  (cursor db_ [:level1 :level2 :level3]))
```

And a "layer 3" subscription function is one that only `deref`s "layer 2" subcriptions:

```clojure
(defn layer-3-sub-fn [db_]
  (make-reaction
    (fn []
      (+ 10 (inc (<sub db_ [a-fn-sub])) (<sub db_ [a-layer-2-fn])))))
```

Now you can subscribe directly to any of these:
```clojure
(<sub db_ [layer-3-sub-fn])
(<sub db_ [a-fn-sub])
```

And invoke them directly, because they are functions:

```clojure
@(layer-3-sub-fn db_)
@(a-fn-sub db_)
```
Notice that they return a Reaction or RCursor and thus must be dereferenced.

It would be great if we didn't have to deref the function to get its value. This sort of thing is the source of unnecessary bugs due
to inconsistencies (sometimes you deref things, sometimes not..)

The library uses the following convention to allow subscribing to functions while also invoking and deref'ing them:

```clojure
(def layer-3-sub-fn (let [sub-fn
                          (fn [db_]
                            (make-reaction
                              (fn []
                                (+ 10 (inc (<sub db_ [a-fn-sub])) (<sub db_ [a-layer-2-fn])))))]
                      (with-meta (fn layer-3-sub-fn [db_ ] @(sub-fn db_)) {:space.matterandvoid.subscriptions.core/subscription sub-fn})))
```

When subscribe is called and a function is passed in the subscription vector the library will first look for a function under
the `:space.matterandvoid.subscriptions.core/subscription` key in the metadata of the function to get the subscription function instead of using the function itself.
If there is not a function in the metadata under the `:space.matterandvoid.subscriptions.core/subscription` key, then the function itself is used.

This is a bit noisy to write by hand, so the library provides to helpers: you can either use the `sub-fn` function helper, or use the `defsub` macro which produces this output for you.

These are equivalent to the above definition:

```clojure
(def layer-3-sub-fn
  (sub-fn (fn [db_]
            (make-reaction (fn []
                             (+ 10 (inc (<sub db_ [a-fn-sub])) (<sub db_ [a-layer-2-fn])))))))

(defsub layer-3-sub-fn :<- [a-fn-sub] :<- [a-layer-2-fn]
  (fn [[num1 num2]] (+ 10 (inc num1) num2)))
```

The main benefits of using functions directly (as explained in the `repose` documentation) are proper module placement for code splitting
and much better editor and IDE integration.
The functions will be analyzed correctly and can be moved to the modules that they are used in, versus using a registry
where all subscriptions will have to be in a common module.
IDE features like jump to definition work as expected instead of having to have special re-frame aware tooling.

You are free to mix and match using the registry (`reg-sub` etc.) and not (subscribing directly to functions).
The registry is used to associate subscription keywords with handler functions and once the handler function is retrieved there is no difference in execution.

## `defregsub` macro

There is a tiny macro in this library which in addition to registering a subscription also outputs a `defn` with the provided name.
When this function is invoked it subscribes and derefs the subscription while passing along any arguments.

Here is an example:

```clojure
(defregsub sorted-todos :<- [::all-todos] :-> (partial sort-by :todo/text))
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

You could also use your own `defregsub` macro to, for example, instrument the calls to subscriptions or manipulate the args map
for all subscriptions.

You probably don't want to use `defregsub` for all subscriptions - possibly just those that are used in components,
and if you really don't care for it you can just use reg-sub and subscribe.

## `defsub` macro

If you are using the library without a registry there is a macro that provides the same syntax as `reg-sub` but expands
to a `defn` which returns the value of the deref'd Reagent Reaction when invoked. This means you can either invoke the function directly with
the data source and optional args map, or pass it to `subscribe` (or use it as an input signal to another subscription).

example:

```clojure
(defsub sorted-todos :<- [all-todos] :-> (partial sort-by :todo/text))

;; use it:
(sorted-todos db_)
(<sub db_ [sorted-todos])
```

# Implementation details

The codebase is quite tiny (the impl.subs namespace, pretty much the same as in re-frame), but if you haven't played with 
reagent Reactions or Ratoms before it can be hard to follow. I found that playing with simple examples helped me to reason
about what is going on.

Subscriptions are implemented as reagent Reaction javascript objects (`deftype` in cljs) and using one helper function `run-in-reaction`.
Reactions have a current value like a ClojureScript atom, but they also have an attached function (the `f` member on the type)
which has the characteristic that when this function body executes if it deref's any ratoms or reactions then the reaction will
remember these in a list of watches. This list of watches is what `run-in-reaction` uses to fire another callback in response
to any depedent `reactions/atom`s updating. - See the `reagent.ratom` namespace, especially `deref-capture`, `in-context`, and `notify-deref-watcher!`.

The implementation of this in reagent is quite elegant - the communication is done via a JavaScript object as shared memory
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
    ;; I honestly don't know what this :no-cache does exactly, but reagent passes this, then so do I :)
    {:no-cache true}))
(swap! base-data inc)
```
To get reactivity we use the reagent helper function `run-in-reaction`, which lets us specify one function to run right now
and one to run reactively. For UIs the reactive callback is where we redraw a component.

I would not have been able to figure this out if the reagent component namespace didn't already exist demonstrating how 
to make this work in practice - definitely refer to the source and play around at a repl to explore this.

## Getting at the subscription cache

If you want to write instrumentation code or just see some of the internals you can look at the subscription cache 
which is located in the var pointed to by the symbol:

```clojure
space.matterandvoid.subscriptions.impl.core/subs-cache
```

This is an atom containing a hashmap of vectors (as passed to subscribe) and values being reactions - if you deref the 
reactions you will get their current value.

You can then `<tap` the values or put them in a UI for dev-time debugging tools.

# Integrating this library with other view layers

In short use `reagent.ratom/run-in-reaction`, see the reagent source for inspiration:

https://github.com/reagent-project/reagent/blob/f64821ce2234098a837ac7e280969f98ab11342e/src/reagent/impl/component.cljs#L254

It takes a `run` function callback which will be invoked when any ratom's or Reactions are deref'd in the main function 
passed to run-in-reaction. In this `run` function you perform the side-effecting re-render.

Or if you're using react hooks, then you don't need to do anything, use the provided hook in this library.

# References 

reagent:

https://github.com/reagent-project/reagent

re-frame:

https://day8.github.io/re-frame/re-frame

Mike Thompson on the history of re-frame:

https://player.fm/series/clojurestream-podcast/s4-e3-re-frame-with-mike-thompson

On my journey to gain understanding with reagent and the re-frame implementation I found the following resources helpful 

(you should definitely play with reagent primitives - reaction, ratom - in a repl)

Historical versions of the re-frame readme provide more in-depth details of how subscriptions are implemented which are 
no longer present in the current documentation. 0.5 for example I found very insightful.

https://github.com/day8/re-frame/tree/v0.5.0#how-flow-happens-in-reagent

FRP in ClojureScript with Javelin

~75% of this talk is about reactive programming. The insights apply directly to subscriptions

https://www.infoq.com/presentations/ClojureScript-Javelin/

I found the symmetry of `(cell ,,,)` in Javelin and `(reaction ,,,)` in Reagent to be clarifying.

Reagent docs on state and reactive updates

https://github.com/reagent-project/reagent/blob/master/doc/ManagingState.md

https://github.com/reagent-project/reagent/blob/master/doc/WhenDoComponentsUpdate.md

# Development and contributing

Clone the repo and run:

```bash
bb dev
```

Open the shadow-cljs builds page and then open the page that hosts the tests or an example app.

Run the clojure tests:

```bash
bb clj-test
```


