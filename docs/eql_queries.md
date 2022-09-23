This library features support for fulfilling EQL queries via subscriptions.

The goals of implementing this were:

1. Use fulcro for client-side applications where fulcro is only used as a data management library and not a UI rendering
   library. The goal was to never rely on `db->tree` and instead fulfill all queries with subscriptions.
2. Extend datomic pull syntax to support declarative graph walking logic, entity resolution, and transformation in a
   recursive/hierarchical fashion

There are two pieces to have this work for your applicaiton.

1. Pick a data source target, like a fulcro app db, XTDB, or Datalevin database.
2. Define your data model using fulcro "naked" components - headless components that are only used for query and
   normalization - and register them, which creates the necessary subscriptions.

After that you can execute EQL queries against your datasource.

Here is an example using datalevin as the data source.

```clojure
(ns my-app.entry
  (:require
    [datalevin.core :as d]
    [space.matterandvoid.subscriptions.core :as subs :refer [<sub]]
    [space.matterandvoid.subscriptions.datalevin-eql :as datalevin.eql :refer [nc query-key xform-fn-key walk-fn-key]]))

(def schema
  {:user/id      {:db/valueType :db.type/keyword :db/unique      :db.unique/identity}
   :user/friends {:db/valueType :db.type/ref     :db/cardinality :db.cardinality/many}
   :user/name    {:db/valueType :db.type/string  :db/unique      :db.unique/identity}

   :bot/id       {:db/valueType :db.type/keyword :db/unique :db.unique/identity}
   :bot/name     {:db/valueType :db.type/string  :db/unique :db.unique/identity}})

(defonce conn (d/get-conn "/tmp/datalevin/mydb" schema))

(def user-comp (nc {:name  ::user ;; the component's name
                    :query [:user/id :user/name {:user/friends '...}] ;; the component's query
                    :ident :user/id})) ;; the component's ident - the unique identifier property for this domain entity.

(def bot-comp (nc {:name  ::bot
                   :query [:bot/id :bot/name]
                   :ident :bot/id}))
```

`nc` is a naked fulcro component, it is exported from the EQL namespaces for convenience. It is a wrapper of the
fulcro `nc` function with a more uniform interface (taking only a hashmap).

After you have your components declared you register them, creating subscriptions for them that can fulfill EQL queries
for them.

```clojure
(run! datalevin.eql/register-component-subs! [user-comp bot-comp])
```

Now we transact some data to query:

```clojure
(d/transact! conn
  [{:user/id :user-7 :user/name "user 7"}
   {:user/id :user-6 :user/name "user 6" :user/friends [[:user/id :user-7]]}
   {:user/id :user-5 :user/name "user 5" :user/friends [[:user/id :user-6] [:user/id :user-7]]}
   {:user/id :user-2 :user/name "user 2" :user/friends [[:user/id :user-2] [:user/id :user-1] [:user/id :user-3] [:user/id :user-5]]}
   {:user/id :user-1 :user/name "user 1" :user/friends [[:user/id :user-2]]}
   {:user/id :user-4 :user/name "user 4" :user/friends [[:user/id :user-3] [:user/id :user-4]]}
   {:user/id :user-3 :user/name "user 3" :user/friends [[:user/id :user-2] [:user/id :user-4]]}
   {:bot/id :bot-1 :bot/name "bot 1"}
   {:db/id "user-10" :user/id :user-10 :user/name "user 10" :user/friends [[:user/id :user-10] [:user/id :user-9] "user-11"]}
   {:db/id "user-11" :user/id :user-11 :user/name "user 11" :user/friends [[:user/id :user-10] "user-12"]}
   {:db/id "user-12" :user/id :user-12 :user/name "user 12" :user/friends [[:user/id :user-11] [:user/id :user-12]]}])
```
In order to have a uniform API for all subscription data sources, your db must be wrapped in an atom, although there 
is currently no reactivitiy for JVM Clojure subscriptions.

```clojure
(defonce db_ (atom (d/db conn)))
```

And now we can run arbitrary EQL queries!

The syntax is as follows, for each registered domain entity there will be a subscription with the name of the component
(the `:name` key passed to `nc`), which is `::user` in this example.
All subscriptions have the shape of a 2-tuple containing a keyword in the first position and a hashmap in the second position.
The hashmap is open, you can put anything you want there.
The EQL implementation makes use of the hashmap to pass your EQL query under the well-known key exported by the library `query-key`
imported in the above namespace `:require` form.

Here we ask for the user with `:user/id` :user-1, pulling three attributes. 
The `0` in the recursive position means do not resolve any nested references, just return them as pointers.

```clojure
(<sub db_ [::user {:user/id :user-1 query-key [:user/name :user/id {:user/friends 0}]}])
; =>

{:db/id "todo-2" :todo/id :todo-2 :todo/text "todo 2" :todo/author [:user/id :user-2]}
```

Currently supported operations are transformation functions and recursive expansion logic:

```clojure
(<sub db_ [::user {`upper-case-name (fn [e] (update e :user/name clojure.string/upper-case))
                     :user/id         :user-1
                     query-key    [:user/name :user/id {(list :user/friends {xform-fn-key `upper-case-name}) 4}]}])
  (<sub db_ [::user {`upper-case-name (fn [e] (update e :user/name str/upper-case))
                     `keep-walking?   (fn [e] (#{"user 1" "user 2"} (:user/name e)))
                     :user/id         :user-1
                     query-key    [:user/name :user/id {(list :user/friends {xform-fn-key `upper-case-name
                                                                             walk-fn-key  `keep-walking?}) '...}]}])
```

The transformation function takes an entity returned from an entity subscription and can return any value, in the above
example we upper-case the `:user/name` attribute - This transform happens in a recursive fashion for any entities found
under the `:user/friends` key.

For the walking function, when the subscription sees unbounded recursion (the `...`) it checks for a symbol under the `walk-fn-key` key,
and uses that symbol to lookup the corresponding function in the paramaters hashmap provided to the subscription and then invokes it
with the data found in the datasource under the corresponding key (`:user/friends` in this example).
Based on the return value of that function the subscription will determine what to do next.
The currenlty supported return values and the semantics of those returns values are:

- a hashmap which currently only supports the keys `:stop` and `:expand`
  - `:stop` is expected to be a collection of refs (normalized pointers for your database) which will be expanded as an entity, but whose recursive property will not continue to be expanded.
  - `:expand` is expected to be a collection of refs (normalized pointers for your database) which will continue to be recursively queried for and expanded as a tree.
  - even if the current node has other refs, if they are not included in either key they will not be included in the output
- a non-hashmap collection of refs - these refs will continue to be walked, any others that may be at the current node but are not present in this collection will not be in the output
- a truthy value - whatever refs are found at the current node will continue to be walked
- a falsey value - stop walking and just return the refs in the output
