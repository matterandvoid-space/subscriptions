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
  {:user/id      {:db/valueType :db.type/keyword :db/unique :db.unique/identity}
   :user/friends {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   :user/name    {:db/valueType :db.type/string :db/unique :db.unique/identity}

   :bot/id       {:db/valueType :db.type/keyword :db/unique :db.unique/identity}
   :bot/name     {:db/valueType :db.type/string :db/unique :db.unique/identity}})

(defonce conn (d/get-conn "/tmp/datalevin/mydb" schema))

(def user-comp (nc {:name  ::user ;; the component's name
                    :query [:user/id :user/name {:user/friends '...}] ;; the component's query
                    :ident :user/id})) ;; the component's ident - the unique identifier property for this domain entity.

(def bot-comp (nc {:name  ::bot
                   :query [:bot/id :bot/name]
                   :ident :bot/id}))
```

`nc` is a naked fulcro component, it is exported from the EQL namespaces for convenience. It is a wrapper of the
fulcro `nc` function with a more uniform interface (taking only a hashmap). If fulcro is not on your classpath a stub
version
is used so that if you don't want to use fulcro you don't have to and the EQL queries feature will still work.

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
   {:user/id :user-2 :user/name "user 2" :user/friends [[:user/id :user-2] -1 -3 [:user/id :user-5]]}
   {:db/id -1 :user/id :user-1 :user/name "user 1" :user/friends [[:user/id :user-2]]}
   {:user/id :user-4 :user/name "user 4" :user/friends [-3 [:user/id :user-4]]}
   {:db/id -3 :user/id :user-3 :user/name "user 3" :user/friends [[:user/id :user-2] [:user/id :user-4]]}
   {:bot/id :bot-1 :bot/name "bot 1"}
   {:db/id -10 :user/id :user-10 :user/name "user 10" :user/friends [-10 -11]}
   {:db/id -11 :user/id :user-11 :user/name "user 11" :user/friends [-10 -12]}
   {:db/id -12 :user/id :user-12 :user/name "user 12" :user/friends [-11 -12]}])
```

In order to have a uniform API for all subscription data sources, your db must be wrapped in an atom, although there
is currently no reactivitiy for JVM Clojure subscriptions.

```clojure
(defonce db_ (atom (d/db conn)))
```

And now we can run arbitrary EQL queries!

The syntax is as follows, for each registered domain entity there will be a subscription with the name of the component
(the `:name` key passed to `nc`), which is `::user` in this example.
All subscriptions have the shape of a 2-tuple containing a keyword in the first position and a hashmap in the second
position.
The hashmap is open, you can put anything you want there.
The EQL implementation makes use of the hashmap to pass your EQL query under the well-known key exported by the
library `query-key`
imported in the above namespace `:require` form.

Here we ask for the user with `:user/id` `:user-1`, pulling three attributes.
The `0` in the recursive position means do not resolve any nested references, just return them as pointers.

```clojure
(<sub db_ [::user {:user/id :user-1 query-key [:user/name :user/id {:user/friends 0}]}])
; =>
{:user/name "user 1", :user/id :user-1, :user/friends [{:db/id 4}]}
```

```clojure
;; expand one more level:
(<sub db_ [::user {:user/id :user-1 query-key [:user/name :user/id {:user/friends 1}]}])
; =>
{:user/name    "user 1",
 :user/id      :user-1,
 :user/friends [{:user/name "user 2", :user/id :user-2, :user/friends [{:db/id 4} {:db/id 6} {:db/id 3} {:db/id 5}]}]}
```

A common query desire is pulling only some of the nodes in a nested fashion based on your application logic, as well as
transforming those nodes in some way, recursively.
The library has support for both of these use cases.

Here we pull 4 levels of friends and also perform a transformation on each friend, at each level.
The transformation function can return anything (which means that if you, for example, replace the `:user/friends` key
in the transform function, the recursion will stop).

The EQL query is provided under the `query-key`, which must be data, so we use EQL params support on the recursion node
(a list containing the key, `:user/friends` in this example, and a hashmap of parameters.) Then provide the
implementation
in the arguments map that the subscription will use.

```clojure
(<sub db_ [::user {`upper-case-name (fn [e] (update e :user/name clojure.string/upper-case))
                   :user/id         :user-1
                   query-key        [:user/name :user/id {(list :user/friends {xform-fn-key `upper-case-name}) 4}]}])

;; =>
{:user/name    "user 1",
 :user/id      :user-1,
 :user/friends [{:user/name    "USER 2",
                 :user/id      :user-2,
                 :user/friends [{:user/name    "USER 2",
                                 :user/id      :user-2,
                                 :user/friends #{{:db/id 4} {:db/id 6} {:db/id 3} {:db/id 5}}}
                                {:user/name    "USER 3",
                                 :user/id      :user-3,
                                 :user/friends [{:user/name    "USER 4",
                                                 :user/id      :user-4,
                                                 :user/friends [{:user/name    "USER 4",
                                                                 :user/id      :user-4,
                                                                 :user/friends [{:db/id 7} {:db/id 6}]}
                                                                {:user/name    "USER 3",
                                                                 :user/id      :user-3,
                                                                 :user/friends [{:db/id 7} {:db/id 4}]}]}
                                                {:user/name    "USER 2",
                                                 :user/id      :user-2,
                                                 :user/friends #{{:db/id 4} {:db/id 6} {:db/id 3} {:db/id 5}}}]}
                                {:user/name    "USER 5",
                                 :user/id      :user-5,
                                 :user/friends [{:user/name "USER 7", :user/id :user-7}
                                                {:user/name    "USER 6",
                                                 :user/id      :user-6,
                                                 :user/friends [{:user/name "USER 7", :user/id :user-7}]}]}
                                {:user/name "USER 1", :user/id :user-1, :user/friends #{{:db/id 4}}}]}]}
```

Note that the starting node is not transformed - the transform applies only to the nodes in the relationship. This lets
you apply a different transform to each entity relationship.

The transformation function takes an entity returned from an entity subscription and can return any value, in the above
example we upper-case the `:user/name` attribute - This transform happens in a recursive fashion for any entities found
under the `:user/friends` key.

If you want to control which nodes are recursively walked, pass the `walk-fn-key` and convert the recursion to
unbounded (using: `'...`).

```clojure
(<sub db_ [::user {`upper-case-name (fn [e] (update e :user/name str/upper-case))
                   `keep-walking?   (fn [e] (#{"user 1" "user 2"} (:user/name e)))
                   :user/id         :user-1
                   query-key        [:user/name :user/id {(list :user/friends {xform-fn-key `upper-case-name
                                                                               walk-fn-key  `keep-walking?}) '...}]}])

{:user/name    "user 1",
 :user/id      :user-1, ; expanded
 :user/friends [{:user/name    "USER 2",
                 :user/id      :user-2,
                 :user/friends [{:user/name "USER 3", :user/id :user-3, :user/friends #{{:db/id 13} {:db/id 10}}} ; not exanded
                                {:user/name "USER 1", :user/id :user-1, :user/friends #{{:db/id 10}}} ; expanded, but cycle so stop
                                {:user/name "USER 5", :user/id :user-5, :user/friends #{{:db/id 7} {:db/id 8}}} ; not expanded
                                {:user/name    "USER 2",
                                 :user/id      :user-2, ; already expanded, cycle so stop walking
                                 :user/friends #{{:db/id 12} {:db/id 11} {:db/id 9} {:db/id 10}}}]}]}
```

For the walking function, when the subscription sees unbounded recursion (the `...`) it checks for a symbol under
the `walk-fn-key` key,
and uses that symbol to lookup the corresponding function in the paramaters hashmap provided to the subscription and
then invokes it
with the data found in the datasource under the corresponding key (`:user/friends` in this example).
Based on the return value of that function the subscription will determine what to do next.
The currenlty supported return values and the semantics of those returns values are:

- a hashmap which currently only supports the keys `:stop` and `:expand`
    - `:stop` is expected to be a collection of refs (normalized pointers for your database) which will be expanded as
      an entity, but whose recursive property will not continue to be expanded.
    - `:expand` is expected to be a collection of refs (normalized pointers for your database) which will continue to be
      recursively queried for and expanded as a tree.
    - even if the current node has other refs, if they are not included in either of the `:stop` or `:expand` keys they
      will not be included in the
      output
- a non-hashmap collection of refs - these refs will continue to be walked, any others that may be at the current node
  but are not present in this collection will not be in the output
- a truthy value - whatever refs are found at the current node will continue to be walked
- a falsey value - stop walking and just return the refs in the output

# Support for subscribing to functions

This library also supports using functions as subscription values where there is no global registry.

The only difference to the above API is that you use the `create-component-subs` function, which returns a subscription
function per component.

For the user component above:

```clojure
(def user-sub (create-component-subs user-comp nil))
```

The `nil` argument is to support components with joins, the subscription functions to fulfill the joins need to be
provided
as there is no global state.

For a made up example, say a user has many notes:

```clojure
(def notes-sub (create-component-subs notes-comp nil))
(def user-sub (create-component-subs user-comp {:user/notes notes-sub}))
```

For union joins we need to provide another level of nesting.

Here's an example where a todo component has an author which can be either a `bot` or a `user`.

```clojure
(def user-comp (nc {:query [:user/id :user/name {:user/friends '...}] :name ::user :ident :user/id}))
(def bot-comp (nc {:query [:bot/id :bot/name] :name ::bot :ident :bot/id}))

;; the author is a union component, you do not create a subscription for union components.
(def author-comp (nc {:query {:bot/id  (get-query bot-comp) :user/id (get-query user-comp)} :name ::author}))
(def todo-comp (nc {:query [:todo/id :todo/text {:todo/author (get-query author-comp)}] :name ::todo :ident :todo/id}))

(def user-sub (create-component-subs user-comp nil))
(def bot-sub (create-component-subs bot-comp nil))
(def todo-sub (create-component-subs todo-comp {:todo/author   
                                                {:bot/id bot-sub :user/id user-sub}}))
```
The `todo-sub`'s joins map has a second level of nesting for the `:todo/author` union. Based on the ref stored in the database
for a specific todo (`[:bot/id :bot-id-1]` or `[:user/id :user-id-1]` for example) the appropriate subscription will be used 
to fulfill the rest of the query.

To subscribe to these you pass the function to `subscribe` or `<sub` functions.

Here was ask for the appropriate name, based on the type of the author found for the todo with id `:todo-1`:

```clojure
(<sub [todo-sub {:todo/id :todo-1 query-key [{:todo/author {:bot/id [:bot/name]
                                                            :user/id [:user/name]}}]}])
```
which would return `{:todo/author {:bot/name "bot"}}` if a bot ref is found and `{:todo/author {:user/name "user"}}` if a user ref is found.

# Notes and gotchas

The implementation assumes that entity IDs are unique across your entire database.
This is mainly only a potential issue for usage with a fulcro DB because you can use a setup like:

```clojure
{:person/id  {1 {:person/id 1 :person/name "a person"}}
 :comment/id {1 {:comment/id           1 :comment/text "FIRST COMMENT"
                 :comment/sub-comments [[:comment/id 2]]}
              2 {:comment/id 2 :comment/text "SECOND COMMENT"}}}
```

In the implementation this is used to track cycles in recursive queries, thus the logic would be faulty because it would
assume the two entities with id 1 are the same, when they are not.

In practice your applications should be using UUIDs or similar, so this shouldn't be an issue, but I'm mentioning it
just in case.
