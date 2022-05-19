2022-05-19

2 things;

you can make an option to control if a component is reactive or not - whether it re-renders aside from the fulcro 
rendering mechanism

or you can opt-in to reactive (or opt out) make it a toggle?

- you can copy the subscription cache to fulcro app db in before-render lifecycle hook
- still need the render middleware and component cleanup installed on fulcro app
- the render middleware is used to populate the subscription cache in both cases (reactive and non)
- in the non-reactive version we just deref the subscriptions in the render body which will populate the 
  subscription cache and allow rendering the most up to date information 
- what is the point of copying to the app db then?
- tony had the thought of storing the in the components ident?

I'm not sure, but I would just start with:
- still use run in reaction - the switch is in the reactive callback.
- in one instance you don't re-render 
- in another you do.

The answer to tony's question about what happens with rendering if you read props and you have a reactive update.
    my solution to this is that when a component refresh's reactively it waits for any fulcro transactions to finish
as the reaction will be caused from a swap, so this may occur.
I think this would answer the question.

I don't fully understand how copying the data to app db solves the stale UI problem though - I think that's the part of 
the picture I don't follow - how the subscription data is tied to the query system.
is it intended to be in the query as well?


so you can make this system configurable to the user:

options to play with
- don't touch the rendering optimization - let the user do that
- reactive or not
- copy to app db or not?

doesn't if it's not reactive mean you have to copy to app db?


I'm forgetting now - maybe his thought was to go with the subscriptions option on the component and then the 
render middleware could use that?

I think he was talking about adding a defsc macro and have the subscriptions as options on the component - like the query

maybe the idea was that the query would be used and pull the data out of app db?

if there's no difference between what is in app db and the current subscription value then you don't ever need to use 
the value in app db.

One downside of copying the subscriptions to app db is that you then double your memory usage for subscriptions, which
could be significant.

I think you can try just storing the subscription cache in the fulcro db under a key like your design supports.

Then - thennnnnnnnnnnn you can just use queries AND the deref will still happen when rendering because the value 
passed to the fulcro component will need to be deref'd - b/c it's a reaction.
maybe you can do this in props middleware or render middleware

props-middlware

=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

It would be ideal to allow extending the implementation of where the subscription cache and handler
registry is stored:

https://github.com/dvingo/cljs-subscriptions/blob/1803189ee3de0f251ae3c46cf72e2409b1c97941/src/re_frame_subs/subs.cljc#L15

It seems like making this a protocol would be the obvious choice:

```clojure
(defprotocol SubStorage
 (-input-db [this])
 (-input-signal-db [this])
 (-subscription-cache [this]))


(require '[com.fulcrologic.fulcro.application :as app])

(defn make-fulcro-state-atom-storage [app]
  (reify SubStorage
    (-input-db [app] (app/current-state app))
    (-input-signal-db [app] (::app/state-atom app))
    (-subscription-cache [this] (atom {}) )))

(defn make-fulcro-runtime-atom-storage [app]
  (reify SubStorage
    (-input-db [app] (-> app ::app/runtime-atom deref)
    (-input-signal-db [app] (::app/runtime-atom app))
    (-subscription-cache [this] (atom {}) )))
```

Then use it:

```clojure
(def app (app/fulcro-app {})
(def subs-storage (make-fulcro-runtime-atom-storage app))

(subs/reg-sub subs-storage :sample-query (fn [db] 500))
(subs/subscribe SPA [:dan-query])
```

This is a little cumbersome though because then you'd have an object that is separate 
from the fulcro app - the idea is that the interface to the library just takes the fulcro app,
or any hashmap really.

Maybe what you would do is a have an interface on top of the protocol construction - such that this library
takes in the hashmap - and that hashmap stores this record instance within it.

Another option is to just use a hashmap of key -> fns to use by the library.. 



Or it is just a construction-time thing:



(subs/make-registry {:input-db (fn [app] (-> ::app/runtime-atom deref))
                     :input-signal-db (fn [app] (-> ::app/runtime-atom))
                     :subscription-cache [_] (atom {})})

That would also result in a new object to pass around though..

------
I think just having a separate namespace that implements the storate mechanism is the way to go.

So you would:

```clojure
(require '[dev.lycurgus.subs.fulcro-runtime-atom :as subs])
(subs/reg-sub SPA :sample-query (fn [db] 500))
(subs/subscribe SPA [:dan-query])
```


Then change the namespace currently called: re-frame-subs.subs to take in functions that 
are passed in instead of a hashmap - this way you can abstract away the storage.


# Enforce subscription schema invocation style of: `[::dispatch-key {:props 'map}]`

[] Add type check that happen when you invoke (subscribe args)


# Create new debounce helper 

in: space.matterandvoid.subscriptions.impl.fulcro

;; prevent multiple reactions triggering this callback in the same frame
;; todo you want to make a new debounce that is invoked the first time it is called within the window
;; and only not called again for the same arguments in the window
;; this is preventing multiple components from firing
(def reaction-callback (debounce reaction-callback* 15))

