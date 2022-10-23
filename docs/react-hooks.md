# React Hooks Subscriptions

This page contains notes on using subscription in react hooks, mainly dealing with how memoization of subscriptions happens.

## On memoization with React hooks

The react hooks internally create a subscription which is an underlying Reagent type like Reaction or Cursor.
This Reagent object is a JavaScript object and thus has some cost to being created.

With React any code will be run anew during each render, thus the subscription object (Reagent Reaction) will be thrown
away and re-created during each render. This is obviously not good for performance.
To deal with this we use [`useMemo`](https://beta.reactjs.org/apis/react/useMemo) to cache the Reaction across renders.
The main issue when caching things is determing when to invalidate the cache and create a new object.

The macros `use-sub-map` and `use-sub-memo` are intended to be the main public API when using this library with React
as they deal with some of the memoization for you, but if you pass arguments to a subscription you have to be aware
that you need to memoize them.

These macros will memoize your subscription vector for you. Then the only concern for you is how to memoize any subscriptions
which use an arguments map.


If your subscription has no arguments:

```clojure
(use-sub-memo [todo.subscriptions/selected-tab])
```

Then this will be optimally performant and you don't have to worry about the underlying memoization.

If you pass a map literal to a subscription as in:
```clojure
(use-sub-memo [todo.subscriptions/selected-tab {:filter current-filter}])
```
The macro will expand to code like:
```clojure
(let [memo-query (react/useMemo (fn [] [todo.subscriptions/selected-tab {:filter current-filter}]) #js[current-filter])]
  (use-sub memo-query))
```
Meaning that it assumes the values in your hashmap are primitives, or are themselves memoized if they are more complex data structures.

If you pass a symbol for the query arguments like:

```clojure
(let [arguments {:filter current-filter}]
  (use-sub-memo [todo.subscriptions/selected-tab arguments]))
```

Then the macro expands to code like:
```clojure
(let [memo-query (react/useMemo (fn [] [todo.subscriptions/selected-tab arguments]) #js[arguments])]
  (use-sub memo-query))
```

Which means you are expected to have memoized `arguments`.

In practice the performant code you should write would be:

```clojure
(let [arguments (react/useMemo (fn [] {:filter current-filter})) #js[current-filter]]
  (use-sub-memo [todo.subscriptions/selected-tab arguments]))
```

`use-sub-map` expands into a hashmap whose values are `(use-sub-memo)` calls, so the same details apply to using it:

```clojure 
(use-sub-map {:form-todo        [todo.subscriptions/form-todo]
              :todos-list       [todo.subscriptions/todos-list-main todo-list-args]
              :selected-tab     [todo.subscriptions/selected-tab {:filter current-filter}]
              :any-completed?   [todo.subscriptions/any-completed?]
              :incomplete-count [todo.subscriptions/incomplete-todos-count]})
```

compiles to code similar to:

```clojure 
(let [hashmap-subs 
      {:form-todo        (use-sub-memo [todo.subscriptions/form-todo])
       :todos-list       (use-sub-memo [todo.subscriptions/todos-list-main todo-list-args])
       :selected-tab     (use-sub-memo [todo.subscriptions/selected-tab {:filter current-filter}])
       :any-completed?   (use-sub-memo [todo.subscriptions/any-completed?])
       :incomplete-count (use-sub-memo [todo.subscriptions/incomplete-todos-count]})]
  hashmap-subs)
```

It's just a convenience macro over repeated `use-sub-memo` calls.

Here is an example:

```clojure
(let [[current-filter set-current-filter!] (react/useState :active)
      todo-list-args (react/useMemo (fn [] {:filter current-filter}) #js[current-filter])
      {:keys [form-todo todos-list incomplete-count selected-tab any-completed?]}
      (use-sub-map {:form-todo        [todo.subscriptions/form-todo]
                    :todos-list       [todo.subscriptions/todos-list-main todo-list-args]
                    :selected-tab     [todo.subscriptions/selected-tab {:filter current-filter}]
                    :any-completed?   [todo.subscriptions/any-completed?]
                    :incomplete-count [todo.subscriptions/incomplete-todos-count]})]
  
  ;; render output here
  )
```

Internally the custom React hook will cache the subscription and invalidates the cache when the query vector changes
between renders. By default [`identical?`](https://cljs.github.io/api/cljs.core/identicalQMARK) is used to determine
if the query vectors are different, this means that it is expected that your query vector is memoized across renders
using `useMemo`, or defined statically outside of your function component.

The hook uses code like the following to determine if the query vector has changed since the prior render:

```clojure
(when-not (equal? (.-current last-query) query)
  (set! (.-current last-query) query)
  (ratom/dispose! (.-current ref))
  (set! (.-current ref) (subs/subscribe datasource query)))
```

All of these subscription hooks use a React Ref to wrap the Reaction.
The reason for doing so is so that React does not re-create the Reaction object each time the component is rendered.

This is safe because the ref's value never changes for the lifetime of the component (per use of use-reaction)
Thus the caution to not read .current from a ref during rendering doesn't apply because we know it never changes.

The guideline exists for refs whose underlying value will change between renders, but we are just using it
as a cache local to the component in order to not recreate the Reaction with each render.

### Configuring memoization via metadata on the subscription

You are able to annotate the subscription argument to `use-sub-memo` and any of the subscriptions in `use-sub-map` with
metadata to both disable memoizing the subscription vector and to change the equality function used to determine when
the subscription vector has changed between re-renders.

For example to disable memoization, use `:no-memo`:

```clojure
(let [arguments {:filter current-filter}]
  (use-sub-memo ^:no-memo [todo.subscriptions/selected-tab arguments]))
```

To change the equality function pass it via `:memo`

```clojure
(let [arguments {:filter current-filter}]
  (use-sub-memo ^{:memo =} [todo.subscriptions/selected-tab arguments]))
```

When using `use-sub-map` you can thus opt-out certain subscriptions from memoization using the same syntax:

```clojure
(let [[current-filter set-current-filter!] (react/useState :active)
      todo-list-args (react/useMemo (fn [] {:filter current-filter}) #js[current-filter])
      {:keys [form-todo todos-list incomplete-count selected-tab any-completed?]}
      (use-sub-map {:form-todo        [todo.subscriptions/form-todo]
                    :todos-list       [todo.subscriptions/todos-list-main todo-list-args]
                    :selected-tab     [todo.subscriptions/selected-tab {:filter current-filter}]
                    :any-completed?   [todo.subscriptions/any-completed?]
                    :some-other-sub   ^:no-memo [todo.subscriptions/my-other-sub]
                    :incomplete-count [todo.subscriptions/incomplete-todos-count]})]

  ;; render output here
  )
```

### Dynamic subscrptions

Things become trickier when the subscription vector contains a symbol which will resolve to a subscription instead of 
being a subscription literal itself.

For example, if you change the subscription dynamically:

```clojure
(let [[done-sub set-done-sub!] (react/useState [todo.subscriptions/incomplete-todos-count])
      the-count (use-sub-memo done-sub)]

  ;; in render
  (d/button {:on-click (fn []
                         (set-done-sub!
                           (if (= done-sub [todo.subscriptions/incomplete-todos-count])
                             [todo.subscriptions/complete-todos-count]
                             [todo.subscriptions/incomplete-todos-count])))} 
    "SWAP Subcription"))
```

The macro will not memoize the subscription vector:

```clojure
(use-sub done-sub)
```

Which means it is up to you to maintain identity of that subscription across re-renders and to memoize it.

Using state will work, as well as memoizing it if the subscription is entering the component via a prop 
(but you will then need to determine when it should be invalidated, so likely the parent component will memoize it).

## References:
- https://beta.reactjs.org/apis/react/useRef#referencing-a-value-with-a-ref
- https://beta.reactjs.org/apis/react/useRef#avoiding-recreating-the-ref-contents
