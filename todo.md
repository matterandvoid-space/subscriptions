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

