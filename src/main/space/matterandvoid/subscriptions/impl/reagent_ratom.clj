(ns space.matterandvoid.subscriptions.impl.reagent-ratom
  (:refer-clojure :exclude [atom])
  (:import [java.util.concurrent Executor Executors]))

;; copied from re-frame interop.clj

;; The purpose of this file is to provide JVM-runnable implementations of the
;; CLJS equivalents in reagent-ratom.cljs.
;;
;; Please note, though, that the purpose here *isn't* to fully replicate all of
;; re-frame's behaviour in a real CLJS environment.  We don't have Reagent or
;; React on the JVM, and we don't try to mimic the stateful lifecycles that they
;; embody.
;;
;; However, if your subscriptions and Reagent render functions are pure, and
;; your side-effects are all managed by effect handlers, then hopefully this will
;; allow you to write some useful tests that can run on the JVM.

(def debug-enabled? true)
(defn ratom? [x] (instance? clojure.lang.Atom x))
(defn atom [x] (clojure.core/atom x))
(defn deref? [x] (instance? clojure.lang.IDeref x))
;; no-op
(defn on-load [listener])
(defonce ^:private executor (Executors/newSingleThreadExecutor))
(defonce ^:private on-dispose-callbacks (atom {}))
(defn cursor [src path] (atom (fn [] (get-in src path))))
(defn reaction? [r] (deref? r))
(defn cursor? [r] (deref? r))
(defn make-reaction
  "On JVM Clojure, return a `deref`-able thing which invokes the given function
  on every `deref`. That is, `make-reaction` here provides precisely none of the
  benefits of `reagent.ratom/make-reaction` (which only invokes its function if
  the reactions that the function derefs have changed value). But so long as `f`
  only depends on other reactions (which also behave themselves), the only
  difference is one of efficiency. That is, your tests should see no difference
  other than that they do redundant work."
  [f]
  (reify clojure.lang.IDeref (deref [_] (f))))
(defn run-in-reaction [f obj key run opts] (f))
(defn in-reactive-context [_ f] (f))

(defn add-on-dispose!
  "On JVM Clojure, use an atom to register `f` to be invoked when `dispose!` is
  invoked with `a-ratom`."
  [a-ratom f]
  (swap! on-dispose-callbacks update a-ratom (fnil conj []) f)
  nil)

(defn dispose!
  "On JVM Clojure, invoke all callbacks registered with `add-on-dispose!` for
  `a-ratom`."
  [a-ratom]
  ;; Try to replicate reagent's behavior, releasing resources first then
  ;; invoking callbacks
  (let [callbacks (get @on-dispose-callbacks a-ratom)]
    (swap! on-dispose-callbacks dissoc a-ratom)
    (doseq [f callbacks] (f))))

(defn reactive-context? [] false)

(defn reagent-id
  "Doesn't make sense in a Clojure context currently."
  [reactive-val]
  "rx-clj")
