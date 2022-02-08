(ns re-frame-subs.interop)

(defn now []
  ;; currentTimeMillis may count backwards in some scenarios, but as this is used for tracing
  ;; it is preferable to the slower but more accurate System.nanoTime.
  (System/currentTimeMillis))

(def debug-enabled? true)

(defonce ^:private on-dispose-callbacks (atom {}))

(defn add-on-dispose!
  "On JVM Clojure, use an atom to register `f` to be invoked when `dispose!` is
  invoked with `a-ratom`."
  [a-ratom f]
  (do (swap! on-dispose-callbacks update a-ratom (fnil conj []) f)
      nil))

(defn make-reaction
  "On JVM Clojure, return a `deref`-able thing which invokes the given function
  on every `deref`. That is, `make-reaction` here provides precisely none of the
  benefits of `reagent.ratom/make-reaction` (which only invokes its function if
  the reactions that the function derefs have changed value). But so long as `f`
  only depends on other reactions (which also behave themselves), the only
  difference is one of efficiency. That is, your tests should see no difference
  other than that they do redundant work."
  [f]
  (reify clojure.lang.IDeref
    (deref [_] (f))))

(defn ratom? [x]
  (instance? clojure.lang.IAtom x))

(defn deref? [x]
  (instance? clojure.lang.IDeref x))

(defn dispose!
  "On JVM Clojure, invoke all callbacks registered with `add-on-dispose!` for
  `a-ratom`."
  [a-ratom]
  ;; Try to replicate reagent's behavior, releasing resources first then
  ;; invoking callbacks
  (let [callbacks (get @on-dispose-callbacks a-ratom)]
    (swap! on-dispose-callbacks dissoc a-ratom)
    (doseq [f callbacks] (f))))

(defn reagent-id
  "Doesn't make sense in a Clojure context currently."
  [reactive-val]
  "rx-clj")

(defn ^boolean reactive-context? [] true)
