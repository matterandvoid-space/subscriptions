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

## Examples

Todo copy things from tests for datascript and for a plain hashmap.

# Differences/modifications from upstream re-frame

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

This is possible because subscriptions are pure functions. 

Another change in this library is that all subscriptions are forced to receive only one argument: a hashmap.

Taking a tip from many successful clojure projects which are able to be extended and grown and integrated over time,
this library forces all subscriptions arguments to be one hashmap - this forces you to name all your arguments and allows
easily flowing data. It also encourages the use of fully qualifed keywords.

That is, they must look like this:

```clojure
(subscribe [::my-sub {:arg1 5}])
```

Doing this will throw an exception:
```clojure
(subscribe [::my-sub {:arg1 5} 'any-other :other "args"])
;; or this
(subscribe [::my-sub "anything that is not a hashmap"])
```

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
