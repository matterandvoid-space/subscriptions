(ns space.matterandvoid.subscriptions.fulcro
  (:require
    [cljs.analyzer :as ana]
    [cljs.env :as cljs-env]
    [clojure.spec.alpha :as s]
    [com.fulcrologic.fulcro.components :as c]

    ;; copied from fulcro.components:
    [edn-query-language.core :as eql]
    [clojure.spec.alpha :as s]
    [clojure.set :as set]
    [taoensso.timbre :as log]
    [clojure.walk :refer [prewalk]]
    [clojure.string :as str]
    [com.fulcrologic.fulcro.algorithms.do-not-use :as util]
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    [com.fulcrologic.fulcro.algorithms.lookup :as ah]
    [com.fulcrologic.fulcro.raw.components :as rc]
    [com.fulcrologic.guardrails.core :refer [>def]]
    [clojure.walk :refer [prewalk]]
    [space.matterandvoid.subscriptions.impl.fulcro :as-alias impl]))

(defn- build-render-orig
  "This functions runs the render body in a reagent Reaction."
  [classsym thissym propsym compsym extended-args-sym body]
  (let [computed-bindings (when compsym `[~compsym (c/get-computed ~thissym)])
        extended-bindings (when extended-args-sym `[~extended-args-sym (c/get-extra-props ~thissym)])
        render-fn-sym     (symbol (str "render-" (name classsym)))]
    `(~'fn ~render-fn-sym [~thissym]
       (let [render-fn#
             (fn [] (c/wrapped-render ~thissym
                      (fn []
                        (let [~propsym (c/props ~thissym)
                              ~@computed-bindings
                              ~@extended-bindings]
                          ~@body))))]
         (setup-reaction! ~thissym render-fn#)
         (render-fn#)))))

(defn- build-render
  "This functions runs the render body in a reagent Reaction."
  [classsym thissym propsym compsym extended-args-sym body]
  (let [computed-bindings (when compsym `[~compsym (c/get-computed ~thissym)])
        extended-bindings (when extended-args-sym `[~extended-args-sym (c/get-extra-props ~thissym)])
        render-fn-sym     (symbol (str "render-" (name classsym)))]
    `(~'fn ~render-fn-sym [~thissym]
       (let [render-fn#
                       (fn [] (c/wrapped-render ~thissym
                                (fn []
                                  (let [~propsym (c/props ~thissym)
                                        ~@computed-bindings
                                        ~@extended-bindings]
                                    ~@body))))
             ^clj reaction# (impl/get-component-reaction ~thissym)]
         (if reaction#
           (do
             (log/info "render macro CALLING RUN")
            (._run reaction# false))
           (do
             (log/info "RENDER macro calling setup reaction")
             (setup-reaction! ~thissym render-fn#)
             ;(render-fn#)
             ))))))

(defn component-will-unmount-form [client-component-will-unmount]
  `(fn [this#]
     (cleanup! this#)
     ~(when client-component-will-unmount `(~client-component-will-unmount this#))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; this is all copied from fulcro.components because they are all marked private and thus compilation fails

(defn- is-link?
  "Returns true if the given query element is a link query like [:x '_]."
  [query-element] (and (vector? query-element)
                    (keyword? (first query-element))
                    ; need the double-quote because when in a macro we'll get the literal quote.
                    (#{''_ '_} (second query-element))))

(defn -legal-keys
  "PRIVATE. Find the legal keys in a query. NOTE: This is at compile time, so the get-query calls are still embedded (thus cannot
  use the AST)"
  [query]
  (letfn [(keeper [ele]
            (cond
              (list? ele) (recur (first ele))
              (keyword? ele) ele
              (is-link? ele) (first ele)
              (and (map? ele) (keyword? (ffirst ele))) (ffirst ele)
              (and (map? ele) (is-link? (ffirst ele))) (first (ffirst ele))
              :else nil))]
    (set (keep keeper query))))

(defn- children-by-prop
  "Part of Defsc macro implementation. Calculates a map from join key to class (symbol)."
  [query]
  (into {}
    (keep #(if (and (map? %) (or (is-link? (ffirst %)) (keyword? (ffirst %))))
             (let [k   (if (vector? (ffirst %))
                         (first (ffirst %))
                         (ffirst %))
                   cls (-> % first second second)]
               [k cls])
             nil) query)))

(defn- replace-and-validate-fn
  "Replace the first sym in a list (the function name) with the given symbol.

  env - the macro &env
  sym - The symbol that the lambda should have
  external-args - A sequence of arguments that the user should not include, but that you want to be inserted in the external-args by this function.
  user-arity - The number of external-args the user should supply (resulting user-arity is (count external-args) + user-arity).
  fn-form - The form to rewrite
  sym - The symbol to report in the error message (in case the rewrite uses a different target that the user knows)."
  ([env sym external-args user-arity fn-form] (replace-and-validate-fn env sym external-args user-arity fn-form sym))
  ([env sym external-args user-arity fn-form user-known-sym]
   (when-not (<= user-arity (count (second fn-form)))
     (throw (ana/error (merge env (meta fn-form)) (str "Invalid arity for " user-known-sym ". Expected " user-arity " or more."))))
   (let [user-args    (second fn-form)
         updated-args (into (vec (or external-args [])) user-args)
         body-forms   (drop 2 fn-form)]
     (->> body-forms
       (cons updated-args)
       (cons sym)
       (cons 'fn)))))

(defn- component-query [query-part]
  (and (list? query-part)
    (symbol? (first query-part))
    (= "get-query" (name (first query-part)))
    query-part))

(defn- compile-time-query->checkable
  "Try to simplify the compile-time query (as seen by the macro)
  to something that EQL can check (`(get-query ..)` => a made-up vector).
  Returns nil if this is not possible."
  [query]
  (try
    (prewalk
      (fn [form]
        (cond
          (component-query form)
          [(keyword (str "subquery-of-" (some-> form second name)))]

          ;; Replace idents with idents that contain only keywords, so syms don't trip us up
          (and (vector? form) (= 2 (count form)))
          (mapv #(if (symbol? %) :placeholder %) form)

          (symbol? form)
          (throw (ex-info "Cannot proceed, the query contains a symbol" {:sym form}))

          :else
          form))
      query)
    (catch Throwable _
      nil)))

(defn- check-query-looks-valid [err-env comp-class compile-time-query]
  (let [checkable-query (compile-time-query->checkable compile-time-query)]
    (when (false? (some->> checkable-query (s/valid? ::eql/query)))
      (let [{:clojure.spec.alpha/keys [problems]} (s/explain-data ::eql/query checkable-query)
            {:keys [in]} (first problems)]
        (when (vector? in)
          (throw (ana/error err-env (str "The element '" (get-in compile-time-query in) "' of the query of " comp-class " is not valid EQL"))))))))

(defn- build-query-forms
  "Validate that the property destructuring and query make sense with each other."
  [env class thissym propargs {:keys [template method]}]
  (cond
    template
    (do
      (assert (or (symbol? propargs) (map? propargs)) "Property args must be a symbol or destructuring expression.")
      (let [to-keyword            (fn [s] (cond
                                            (nil? s) nil
                                            (keyword? s) s
                                            :otherwise (let [nspc (namespace s)
                                                             nm   (name s)]
                                                         (keyword nspc nm))))
            destructured-keywords (when (map? propargs) (util/destructured-keys propargs))
            queried-keywords      (-legal-keys template)
            has-wildcard?         (some #{'*} template)
            to-sym                (fn [k] (symbol (namespace k) (name k)))
            illegal-syms          (mapv to-sym (set/difference destructured-keywords queried-keywords))
            err-env               (merge env (meta template))]
        (when-let [child-query (some component-query template)]
          (throw (ana/error err-env (str "defsc " class ": `get-query` calls in :query can only be inside a join value, i.e. `{:some/key " child-query "}`"))))
        (when (and (not has-wildcard?) (seq illegal-syms))
          (throw (ana/error err-env (str "defsc " class ": " illegal-syms " was destructured in props, but does not appear in the :query!"))))
        `(~'fn ~'query* [~thissym] ~template)))
    method
    (replace-and-validate-fn env 'query* [thissym] 0 method)))

(defn- build-ident
  "Builds the ident form. If ident is a vector, then it generates the function and validates that the ID is
  in the query. Otherwise, if ident is of the form (ident [this props] ...) it simply generates the correct
  entry in defsc without error checking."
  [env thissym propsarg {:keys [method template keyword]} is-legal-key?]
  (cond
    keyword (if (is-legal-key? keyword)
              `(~'fn ~'ident* [~'_ ~'props] [~keyword (~keyword ~'props)])
              (throw (ana/error (merge env (meta template)) (str "The table/id " keyword " of :ident does not appear in your :query"))))
    method (replace-and-validate-fn env 'ident* [thissym propsarg] 0 method)
    template (let [table   (first template)
                   id-prop (or (second template) :db/id)]
               (cond
                 (nil? table) (throw (ana/error (merge env (meta template)) "TABLE part of ident template was nil" {}))
                 (not (is-legal-key? id-prop)) (throw (ana/error (merge env (meta template)) (str "The ID property " id-prop " of :ident does not appear in your :query")))
                 :otherwise `(~'fn ~'ident* [~'this ~'props] [~table (~id-prop ~'props)])))))


(defn- build-hooks-render [classsym thissym propsym compsym extended-args-sym body]
  (let [computed-bindings (when compsym `[~compsym (com.fulcrologic.fulcro.components/get-computed ~thissym)])
        extended-bindings (when extended-args-sym `[~extended-args-sym (com.fulcrologic.fulcro.components/get-extra-props ~thissym)])
        render-fn         (symbol (str "render-" (name classsym)))]
    `(~'fn ~render-fn [~thissym ~propsym]
       (com.fulcrologic.fulcro.components/wrapped-render ~thissym
         (fn []
           (binding [*app*    (or *app* (isoget-in ~thissym ["props" "fulcro$app"]))
                     *depth*  (inc (or *depth* (isoget-in ~thissym ["props" "fulcro$depth"])))
                     *shared* (shared (or *app* (isoget-in ~thissym ["props" "fulcro$app"])))
                     *parent* ~thissym]
             (let [~@computed-bindings
                   ~@extended-bindings]
               ~@body)))))))

(defn- build-and-validate-initial-state-map [env sym initial-state legal-keys children-by-query-key]
  (let [env           (merge env (meta initial-state))
        join-keys     (set (keys children-by-query-key))
        init-keys     (set (keys initial-state))
        illegal-keys  (if (set? legal-keys) (set/difference init-keys legal-keys) #{})
        is-child?     (fn [k] (contains? join-keys k))
        param-expr    (fn [v]
                        (if-let [kw (and (keyword? v) (= "param" (namespace v))
                                      (keyword (name v)))]
                          `(~kw ~'params)
                          v))
        parameterized (fn [init-map] (into {} (map (fn [[k v]] (if-let [expr (param-expr v)] [k expr] [k v])) init-map)))
        child-state   (fn [k]
                        (let [state-params    (get initial-state k)
                              to-one?         (map? state-params)
                              to-many?        (and (vector? state-params) (every? map? state-params))
                              code?           (list? state-params)
                              from-parameter? (and (keyword? state-params) (= "param" (namespace state-params)))
                              child-class     (get children-by-query-key k)]
                          (when code?
                            (throw (ana/error env (str "defsc " sym ": Illegal parameters to :initial-state " state-params ". Use a lambda if you want to write code for initial state. Template mode for initial state requires simple maps (or vectors of maps) as parameters to children. See Developer's Guide."))))
                          (cond
                            (not (or from-parameter? to-many? to-one?)) (throw (ana/error env (str "Initial value for a child (" k ") must be a map or vector of maps!")))
                            to-one? `(com.fulcrologic.fulcro.components/get-initial-state ~child-class ~(parameterized state-params))
                            to-many? (mapv (fn [params]
                                             `(com.fulcrologic.fulcro.components/get-initial-state ~child-class ~(parameterized params)))
                                       state-params)
                            from-parameter? `(com.fulcrologic.fulcro.components/get-initial-state ~child-class ~(param-expr state-params))
                            :otherwise nil)))
        kv-pairs      (map (fn [k]
                             [k (if (is-child? k)
                                  (child-state k)
                                  (param-expr (get initial-state k)))]) init-keys)
        state-map     (into {} kv-pairs)]
    (when (seq illegal-keys)
      (throw (ana/error env (str "Initial state includes keys " illegal-keys ", but they are not in your query."))))
    `(~'fn ~'build-initial-state* [~'params] (com.fulcrologic.fulcro.components/make-state-map ~initial-state ~children-by-query-key ~'params))))

(defn- build-raw-initial-state
  "Given an initial state form that is a list (function-form), simple copy it into the form needed by defsc."
  [env method]
  (replace-and-validate-fn env 'build-raw-initial-state* [] 1 method))

(defn- build-initial-state [env sym {:keys [template method]} legal-keys query-template-or-method]
  (when (and template (contains? query-template-or-method :method))
    (throw (ana/error (merge env (meta template)) (str "When query is a method, initial state MUST be as well."))))
  (cond
    method (build-raw-initial-state env method)
    template (let [query    (:template query-template-or-method)
                   children (or (children-by-prop query) {})]
               (build-and-validate-initial-state-map env sym template legal-keys children))))


(defn build-ident
  "Builds the ident form. If ident is a vector, then it generates the function and validates that the ID is
  in the query. Otherwise, if ident is of the form (ident [this props] ...) it simply generates the correct
  entry in defsc without error checking."
  [env thissym propsarg {:keys [method template keyword]} is-legal-key?]
  (cond
    keyword (if (is-legal-key? keyword)
              `(~'fn ~'ident* [~'_ ~'props] [~keyword (~keyword ~'props)])
              (throw (ana/error (merge env (meta template)) (str "The table/id " keyword " of :ident does not appear in your :query"))))
    method (replace-and-validate-fn env 'ident* [thissym propsarg] 0 method)
    template (let [table   (first template)
                   id-prop (or (second template) :db/id)]
               (cond
                 (nil? table) (throw (ana/error (merge env (meta template)) "TABLE part of ident template was nil" {}))
                 (not (is-legal-key? id-prop)) (throw (ana/error (merge env (meta template)) (str "The ID property " id-prop " of :ident does not appear in your :query")))
                 :otherwise `(~'fn ~'ident* [~'this ~'props] [~table (~id-prop ~'props)])))))

(defn defsc*
  [env args]
  (when-not (s/valid? ::c/args args)
    (throw (ana/error env (str "Invalid arguments. " (-> (s/explain-data ::c/args args) ::s/problems first :path) " is invalid."))))
  (let [{:keys [sym doc arglist options body]} (s/conform ::c/args args)
        [thissym propsym computedsym extra-args] arglist
        _                                (when (and options (not (s/valid? ::c/options options)))
                                           (let [path    (-> (s/explain-data ::c/options options) ::s/problems first :path)
                                                 message (cond
                                                           (= path [:query :template]) "The query template only supports vectors as queries. Unions or expression require the lambda form."
                                                           (= :ident (first path)) "The ident must be a keyword, 2-vector, or lambda of no arguments."
                                                           :else "Invalid component options. Please check to make\nsure your query, ident, and initial state are correct.")]
                                             (throw (ana/error env message))))
        {:keys [ident query initial-state]} (s/conform ::c/options options)
        body                             (or body ['nil])
        ident-template-or-method         (into {} [ident]) ;clojure spec returns a map entry as a vector
        initial-state-template-or-method (into {} [initial-state])
        query-template-or-method         (into {} [query])
        validate-query?                  (and (:template query-template-or-method) (not (some #{'*} (:template query-template-or-method))))
        legal-key-checker                (if validate-query?
                                           (or (-legal-keys (:template query-template-or-method)) #{})
                                           (complement #{}))
        ident-form                       (build-ident env thissym propsym ident-template-or-method legal-key-checker)
        state-form                       (build-initial-state env sym initial-state-template-or-method legal-key-checker query-template-or-method)
        query-form                       (build-query-forms env sym thissym propsym query-template-or-method)
        _                                (when validate-query?
                                           ;; after build-query-forms as it also does some useful checks
                                           (check-query-looks-valid env sym (:template query-template-or-method)))
        hooks?                           (and (c/cljs? env) (:use-hooks? options))
        render-form                      (if hooks?
                                           (build-hooks-render sym thissym propsym computedsym extra-args body)
                                           (build-render sym thissym propsym computedsym extra-args body))
        nspc                             (if (c/cljs? env) (-> env :ns :name str) (name (ns-name *ns*)))
        fqkw                             (keyword (str nspc) (name sym))
        options-map                      (cond-> options
                                           true (assoc :componentWillUnmount (component-will-unmount-form (:componentWillUnmount options)))
                                           state-form (assoc :initial-state state-form)
                                           ident-form (assoc :ident ident-form)
                                           query-form (assoc :query query-form)
                                           hooks? (assoc :componentName fqkw)
                                           render-form (assoc :render render-form))]
    (cond
      hooks?
      `(do
         (defonce ~sym
           (fn [js-props#]
             (let [render# (:render (c/component-options ~sym))
                   [this# props#] (c/use-fulcro js-props# ~sym)]
               (render# this# props#))))
         (c/add-hook-options! ~sym ~options-map))

      (c/cljs? env)
      `(do
         (declare ~sym)
         (let [options# ~options-map]
           (defonce ~(vary-meta sym assoc :doc doc :jsdoc ["@constructor"])
             (c/react-constructor (get options# :initLocalState)))
           (c/configure-component! ~sym ~fqkw options#)))

      :else
      `(do
         (declare ~sym)
         (let [options# ~options-map]
           (def ~(vary-meta sym assoc :doc doc :once true)
             (c/configure-component! ~(str sym) ~fqkw options#)))))))

(defmacro ^{:doc      "Define a stateful component. This macro emits a React UI class with a query,
   optional ident (if :ident is specified in options), optional initial state, optional css, lifecycle methods,
   and a render method. It can also cause the class to implement additional protocols that you specify. Destructuring is
   supported in the argument list.

   The template (data-only) versions do not have any arguments in scope
   The lambda versions have arguments in scope that make sense for those lambdas, as listed below:

   ```
   (defsc Component [this {:keys [db/id x] :as props} {:keys [onSelect] :as computed} extended-args]
     {
      ;; stateful component options
      ;; query template is literal. Use the lambda if you have ident-joins or unions.
      :query [:db/id :x] ; OR (fn [] [:db/id :x]) ; this in scope
      ;; ident template is table name and ID property name
      :ident [:table/by-id :id] ; OR (fn [] [:table/by-id id]) ; this and props in scope
      ;; initial-state template is magic..see dev guide. Lambda version is normal.
      :initial-state {:x :param/x} ; OR (fn [params] {:x (:x params)}) ; nothing is in scope
      ;; pre-merge, use a lamba to modify new merged data with component needs
      :pre-merge (fn [{:keys [data-tree current-normalized state-map query]}] (merge {:ui/default-value :start} data-tree))

      ; React Lifecycle Methods (for the default, class-based components)
      :initLocalState            (fn [this props] ...) ; CAN BE used to call things as you might in a constructor. Return value is initial state.
      :shouldComponentUpdate     (fn [this next-props next-state] ...)

      :componentDidUpdate        (fn [this prev-props prev-state snapshot] ...) ; snapshot is optional, and is 16+. Is context for 15
      :componentDidMount         (fn [this] ...)
      :componentWillUnmount      (fn [this] ...)

      ;; DEPRECATED IN REACT 16 (to be removed in 17):
      :componentWillReceiveProps        (fn [this next-props] ...)
      :componentWillUpdate              (fn [this next-props next-state] ...)
      :componentWillMount               (fn [this] ...)

      ;; Replacements for deprecated methods in React 16.3+
      :UNSAFE_componentWillReceiveProps (fn [this next-props] ...)
      :UNSAFE_componentWillUpdate       (fn [this next-props next-state] ...)
      :UNSAFE_componentWillMount        (fn [this] ...)

      ;; ADDED for React 16:
      :componentDidCatch         (fn [this error info] ...)
      :getSnapshotBeforeUpdate   (fn [this prevProps prevState] ...)

      ;; static.
      :getDerivedStateFromProps  (fn [props state] ...)

      ;; ADDED for React 16.6:
      ;; NOTE: The state returned from this function can either be:
      ;; a raw js map, where Fulcro's state is in a sub-key: `#js {\"fulcro$state\" {:fulcro :state}}`.
      ;; or a clj map. In either case this function will *overwrite* Fulcro's component-local state, which is
      ;; slighly different behavior than raw React (we have no `this`, so we cannot read Fulcro's state to merge it).
      :getDerivedStateFromError  (fn [error] ...)

      NOTE: shouldComponentUpdate should generally not be overridden other than to force it false so
      that other libraries can control the sub-dom. If you do want to implement it, then old props can
      be obtained from (prim/props this), and old state via (gobj/get (. this -state) \"fulcro$state\").

      ; React Hooks support
      ;; if true, creates a function-based instead of a class-based component, see the Developer's Guide for details
      :use-hooks? true

      ; BODY forms. May be omitted IFF there is an options map, in order to generate a component that is used only for queries/normalization.
      (dom/div #js {:onClick onSelect} x))
   ```

   NOTE: The options map is \"open\". That is: you can add whatever extra stuff you want to in order
   to co-locate data for component-related concerns. This is exactly what component-local css, the
   dynamic router, and form-state do.  The data that you add is available from `comp/component-options`
   on the component class and instances (i.e. `this`).

   See the Developer's Guide at book.fulcrologic.com for more details.
   "
            :arglists '([this dbprops computedprops]
                        [this dbprops computedprops extended-args])}
  defsc
  [& args]
  (try
    (defsc* &env args)
    (catch Exception e
      (if (contains? (ex-data e) :tag)
        (throw e)
        (throw (ana/error &env "Unexpected internal error while processing defsc. Please check your syntax." e))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(defn get-fulcro-component-query-keys
  []
  (let [query-nodes        (some-> class (rc/get-query) (eql/query->ast) :children)
        query-nodes-by-key (into {}
                             (map (fn [n] [(:dispatch-key n) n]))
                             query-nodes)
        {props :prop joins :join} (group-by :type query-nodes)
        join-keys          (->> joins (map :dispatch-key) set)
        prop-keys          (->> props (map :dispatch-key) set)]
    {:join join-keys :leaf prop-keys}))

;; copied query handling from fulcro.form-state.derive-form-info
(defn component->subscriptions
  "todo
  The idea here is to register subscriptions for the given component based on its query. "
  [com])

(defmacro defsub
  "Has the same function signature as `reg-sub`.
  Registers a subscription and creates a function which is invokes subscribe and deref on the registered subscription
  with the args map passed in."
  [sub-name & args]
  (let [sub-kw (keyword (str *ns*) (str sub-name))]
    `(do
       (reg-sub ~sub-kw ~@args)

       (defn ~sub-name
         ([app#] (deref (subscribe app# [~sub-kw])))
         ([app# args#] (deref (subscribe app# [~sub-kw args#])))))))
