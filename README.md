This is an extraction of re-frame subscriptions into its own library, where the db (as a reagent.ratom/atom) is always
passed explicitly to `(subscribe)` calls instead of accessed via a global Var.

The original intent was to use subscriptions with fulcro, but the library can be used with any data source that is 
wrapped in reagent.ratom/atom.

This library only has a dependency on the `reagent.ratom` namespace from the reagent codebase.

# Integrations

There are two API entry namespaces (for now) - one for use with fulcro and one for general use with any datasource.

See docs/fulcro.md for details on usage with fulcro.

The reg-sub API is the same as in re-frame (the subscription handlers are stored in a global var, but this can be easily
changed if you desire, and then the API becomes: `(reg-sub your-registry-value :hello (fn [db] (:hello db)))`)

The difference from upstream is when you invoke `(subscribe)` you pass in the root of the subscription DAG: 
```clojure
(subscribe (reagent.ratom/atom {:hello 200}) [:hello])
```

# Examples

Todo copy things from tests for datascript and for a plain hashmap.

## Use with a hashmap

## Use with datascript

# Differences/modifications from upstream re-frame


(inputs-fn to a reg-sub takes the storage (as a reagent.ratom/atom) 
and the subscribe vector)

## Memoized subscription computation functions.

The underlying reagent.ratom/Reaction used in re-frame is cached - this library also does this.

The issue is that this leads to memory leaks if you attempt to invoke a subscription outside of the reactive propagation stage.

That is, the reaction cache is used for example in a re-frame web app when a `reset!` is called on the reagent.ratom/atom
app-db - this triggers reagent code that will re-render views, it is during this stage that the subscription computation function
runs and the reaction cache is successfully used.

The key part is that reagent adds an on-dispose handler for the reaction which is invoked when a component unmounts.

Thus, if you try to use a subscription outside of the reactive context the subscription's reaction will be cached
but never disposed, consuming memory that is never relinquished until the application is restarted.

This library incorporates two changes to make sure there are no memory leaks and that subscriptions can be used in any context 
- with a cache in both contexts.

The changes are:

- do not cache reactions if we are not in a reactive context (reagent indicates a reactive context by binding a dynamic variable.)
- memoize all subscription computation functions with a bounded cache that evicts the least recently used subscription when full.

This is possible because subscriptions are pure functions and the layer 2 accessor subscriptions will invalidate when a new value 
for app-db is `reset!`.

## Only one map for all queries

Another change in this library is that all subscriptions are forced to receive only one argument: a hashmap.

Taking a tip from many successful clojure projects which are able to be extended and grown and integrated over time,
(as well as dealing with multiple subscription argument styles in a large re-frame app and attempting to refactor them..)
This library forces all subscriptions arguments to be one hashmap - this forces you to name all your arguments and allows
easily flowing data. It also encourages the use of fully qualified keywords.

This format of a 2-tuple with a tag as the first element and data as the second shows up in lots of places, here is 
a great talk about modeling information this way by Jeanine Adkisson from the 2014 Conj:

[Jeanine Adkisson - Variants are Not Unions](https://www.youtube.com/watch?v=ZQkIWWTygio)

Concretely, all subscribe calls must have this shape::

```clojure
(subscribe [::my-sub {:arg1 5}])
```

Doing this will throw an exception:
```clojure
(subscribe [::my-sub {:arg1 5} 'any-other :other "args"])
;; or this
(subscribe [::my-sub "anything that is not a hashmap"])
```

## Event keyword is never passed to any callbacks

I'm sure you may notice if you've used re-frame before that the query id is never used in actual code - neither to 
produce the input signals, or in the computation function.

This library removes another point where a decision has to be made about how the callbacks will be called - they are 
always passed the source of your data (usually a ratom) and the query args.

Here's an example where we query for a list of todos, where the data is normalized

```clojure
(defonce db_ (reageent.ratom/atom {:list-one [#uuid"c906f43e-b91d-464d-88cb-0c54988ee847"
                                              #uuid"62864412-d146-4111-b339-8fb3f5f5d236"]
                                   :todo/id {#uuid"c906f43e-b91d-464d-88cb-0c54988ee847" #:todo{:id #uuid"c906f43e-b91d-464d-88cb-0c54988ee847",
                                                                                               :text "todo1",
                                                                                               :state :incomplete},
                                            #uuid"62864412-d146-4111-b339-8fb3f5f5d236" #:todo{:id #uuid"62864412-d146-4111-b339-8fb3f5f5d236",
                                                                                               :text "todo2",
                                                                                               :state :incomplete},
                                            #uuid"f4aa3501-0922-47a5-8579-70a4f3b1398b" #:todo{:id #uuid"f4aa3501-0922-47a5-8579-70a4f3b1398b",
                                                                                               :text "todo3",
                                                                                               :state :incomplete}}}))
(reg-sub ::item-ids #(get %1 (:list-id %2)))

(reg-sub ::todos-list
  (fn [ratom {:keys [list-id]}] ;; <-- here we don't need useless vector destructuring.
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
to operate on the db).

## `defsub` macro

Creates a function that derefs the subscription.

There is a tiny macro in addition to registering a subscription also creates a `defn` with the provided name.
When this function is invoked it proxies to: `(deref (subscribe [::subscription-here arg]))`

This allows for better editor integration such as jump-to-definition support as well as searching for the use of the
subscription across a codebase.

You could also use your own defsub macro to add instrumentation, for example, around subscriptions.

# Development 

clone the repo and: 

```bash
bb dev
```

Open the shadow-cljs inspector page and open the page that hosts the tests.

# Integrating this library with other view layers

In short use `reagent.ratom/run-in-reaction`, see the reagent source for inspiration:

https://github.com/reagent-project/reagent/blob/f64821ce2234098a837ac7e280969f98ab11342e/src/reagent/impl/component.cljs#L254

# References 

re-frame:

https://day8.github.io/re-frame/re-frame

Mike Thompson on the history of re-frame:

https://player.fm/series/clojurestream-podcast/s4-e3-re-frame-with-mike-thompson

https://soundcloud.com/clojurestream/s4-e3-re-frame-with-mike-thompson
