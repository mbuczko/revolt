# Introduction

Clojure projects historically leverage the power of two glorious build tools: [leiningen](https://leiningen.org/) and [boot](http://boot-clj.com/). Both battle-tested,
feature rich alternatives allow to choose either declarative (leiningen) or fully programmable way to manage with tons of dependencies, _dev_ / _prod_ builds and whole
bunch of tasks crucial for clojure developer.

The choice was relatively easy. Up util recent.

One day, according to their tradition, Cognitect surprisingly announced a new player on the stage - a command line tools for running Clojure programs 
([Deps and CLI Guide](https://clojure.org/guides/deps_and_cli)). It was not only about a new tool, the most significant improvement presented to community was 
entirely new way of working with dependencies. Things like multi-module project with separate dependencies and dependencies stright from git repo became possible
just right out of the box.

Despite of this awesomeness people started wondering how to join all these toys together. Drop lein/boot and go along with Cognitect way or use new deps for 
real application-dependencies and still leverage boot/lein tools for development only? Or maybe ignore that new kid on the block and stick with bullet-proof
tools we used for ages?

One of most interesting moves within this area was JUXT's [edge](https://juxt.pro/blog/posts/edge.html) and their attempt to build a simple but complete Clojure project
based on the newest and most experimental things found around. Yeah, including Cognitect's new dependencies.

_Revolt_ is inspired by JUXT's edge and, at its core, tries to simplify attaching all these shiny tools to the project by gathering them in form of tasks and plugins.
And yes, it depends on Cognitect's dependencies underneath and makes heavy use of newly introduced aliases by the way.

Doesn't it sound like a [Lisp Curse](http://www.winestockwebdesign.com/Essays/Lisp_Curse.html) again? :)

# What's in the box?

A couple of plugins you may really like:

- [x] [Rebel REPL](https://github.com/bhauman/rebel-readline) to give you best REPL experience
- [x] [Figwheel](https://github.com/bhauman/lein-figwheel) must-have for web development
- [x] [nREPL](https://github.com/clojure/tools.nrepl) obviously to let you use Emacs and Cider
- [x] Filesystem watcher able to watch and react on files changes

and a few built-in tasks:

- [x] scss - transforms scss files into css
- [x] cljs - a cljs compiler
- [x] aot - ahead-of-time compilation
- [x] test - clojure.test runner based on Metosin's [bat-test](https://github.com/metosin/bat-test)
- [x] info - project info (name, description, package, version, git branch, sha...)
- [ ] lint - linter based on [eastwood](https://github.com/jonase/eastwood)
- [ ] analyse - static code analyzer based on [kibit](https://github.com/jonase/kibit)
- [ ] uberjar - [onejar](http://one-jar.sourceforge.net/) packaging
- [x] capsule - [capsule](http://www.capsule.io) packaging
- [ ] ancient - looking for outdated dependencies
- [x] codox - API documentation with [codox](https://github.com/weavejester/codox)

## Plugins

Plugins are one these guys who always cause problems. No matter if that's boot or lein, they just barely fit into architecture with what they do. And they do a lot of
weird things, eg. nREPL is a socket server waiting for connection, REPL is a command line waiting for input, watcher on the other hand is a never ending loop watching
for file changes. Apples and oranges put together into same basket.

_Revolt_ does not try to unify them and pretend they're same tasks as cljs compilation or scss transformation. They are simply a piece of code which starts when asked and
stops working when JVM shuts down. Nothing more than that. Technically, plugins (as well as tasks) are identified by qualified keyword and configured in a separate file.
Typical configuration looks like following:

```clojure
{:revolt.plugin/nrepl {:port 5600}
 :revolt.plugin/rebel {:init-ns "foo.system"}
 :revolt.plugin/watch {:excluded-paths ["src/clj"]
                       :on-change {:revolt.task/sass "glob:assets/styles/*.scss"}}}
```
Right after activation plugins usually stay in a separate thread until deactivation phase hits them in a back which happens on JVM shutdown, triggered for example when plugin 
running in a main thread (like `rebel`) gets interrupted.

Plugins love to delegate their job down to someone else, as all those bad guys do. In our cruel world these are _tasks_ who handle most of ungrateful work on behalf of Master Plugins.
As an example: `watch` plugin observes changes in a filesytem and calls a `sass` task when *.scss file is altered. Sometimes, task _has_ to be explicitly configured to have plugin
working, in other words task becomes a plugin's dependency and you will see a lot of cry and complain when such a dependency is not configured correctly.

Ok, but how to specify which plugins do we want to activate? This is where `clj` tool from Cognitect comes onto scene, but more on that a bit later...

## Tasks

If we called plugins as "bad guys", tasks are definitely the opposite - kind of little dwarfs who are specialized to do one job and do it well. And similar to plugins, there is a bunch
of built-in tasks ready to serve you and take care of building and packaging your application. Oh, and they can generate documentation too.

To understand how tasks work, imagine them as a chain of dwarfs, each of them doing specific job and passing result to the next one:

    clean ⇒ info ⇒ sass ⇒ cljs ⇒ capsule

which can expressed as a composition:

    (capsule (cljs (sass (info (clean)))))

or in a bit more clojurey way:

``` clojure
(def build (comp capsule cljs sass info clean))
```

This way calling a `build` composition will clean a target directory, generate project information (name, group, version, git sha...), generate CSSes and finally pack everything into an uberjar 
(a capsule actually). Each of these tasks may generate intermediate result and pass it as a map to the next one in a `context`, eg. `info` task gathers project related information which is at the end
passed to `capsule` which in turn makes use of these bits to generate a correct package.

To have even more fun, each task can be pre-configured in a very similar way as plugins are:

``` clojure
:revolt.task/info  {:name "foo"
                    :group "bar.bazz"
                    :version "0.0.1"
                    :description "My awesome project"}

:revolt.task/test  {:report :pretty}

:revolt.task/sass  {:resources ["styles/main.scss" "styles/login.scss"]}

:revolt.task/codox {:source-paths ["src/clj"]
                    :source-uri "http://github.com/fuser/foo/blob/{version}/{filepath}#L{line}"
                    :namespaces [foo.main foo.core]}

:revolt.task/cljs  {:builds [{:id "main-ui"
                              :figwheel true
                              :source-paths ["src/cljs"]
                              :compiler {:main "foo.main"
                                         :output-to "scripts/main.js"
                                         :output-dir "scripts/out"
                                         :asset-path "/scripts/core"
                                         :preloads [devtools.preload]}}]}

:revolt.task/capsule {:exclude-paths #{"test" "src/cljs"}
                      :output-jar "dist/foo.jar"
                      :capsule-type :fat
                      :main "foo.main"
                      :min-java-version "1.8.0"
                      :jvm-args "-server"
                      :caplets {"MavenCapsule" [["Repositories" "central clojars(https://repo.clojars.org/)"]
                                                ["Allow-Snapshots" "true"]]}}
```

Let's talk about task arguments now.

Having tasks configured doesn't mean they are sealed and we can't bend them to our needs any more. Let's look at the `sass` task as an example. Although it generates CSSes based on
configured `:resources`, as all other tasks this one also accepts an argument which can be one of following types:

 - A keyword. This type of arguments is automatically handled by _revolt_. As for now only `:describe` responds - returns a human readable description of given task.
 - A `java.nio.file.Path`. This type of arguments is also automatically handled by _revolt_ and is considered as a notification that particular file has been changed and task should react upon. 
 `sass` task uses path to filter already configured `:resources` and rebuilds only a subset of SCSSes (if possible).
 - A map. Actually it's up to tasks how to handle incoming map argument, by convension _revolt_ simply merges incoming map into existing configuration:

``` clojure
(info {:environment :testing})
⇒ {:name "foo", :group "bar.bazz", :version "0.0.1", :description "My awesome project", :environment :testing}
```

Obviously we can provide an argument in a composition too:

``` clojure
(def build (comp capsule cljs sass (partial info {:environment :testing}) clean))
```

Why this is so cool? Because this way we can play with our builds and packaging in a REPL without changing a single line of base configuration. Eg. to generate a thin package with an optimized
version of our clojurescripts, we can build a following pipeline:

``` clojure
(def build (comp (partial capsule {:capsule-type :thin})
                 (partial cljs {:optimizations :advanced})
                 sass
                 info
                 clean))
```
Alright, so we know already how tasks work. We know they modify and pass down a context in a composition chain, and they accept an argument which can be merged into their base configuration. 
Now, how can we get these tasks into our hands?

Well, quite easy. As you remember tasks are denoted by qualified keywords, like `:revolt.task/capsule`. All we need is now to _require-a-task_ :

``` clojure
(require '[revolt.task :as t])  ;; we need a task namespace first
(t/require-task ::t/capsule)    ;; now we can require specific task

(capsule)                       ;; task has been interned into current namespace
⇒ {:uberjar "dist/foo.jar"}
```

Indeed, `require-task` is a macro which does the magic, it loads and interns into current namespace required task. It's also possible to intern a task with different name:

``` clojure
(t/require-task ::t/capsule :as caps)  ;; note the ":as caps" here

(caps)
⇒ {:uberjar "dist/foo.jar"}
```

or to save unnecessary typing and load a bunch of tasks at once with `require-all` macro:

``` clojure
(t/require-all [::t/clean ::t/cljs ::t/sass ::t/capsule ::t/aot ::t/info])
(def build (comp capsule cljs sass info clean))

(build)
⇒ { final context is returned here }
```

`require-task` and `require-all` are simple ways to dynamically load tasks we want to play with and by chance turn our REPLs into training ground where all tasks are impatiently waiting to be
used and abused :)


## Usage

Ok, so having now both plugins and tasks at our disposal, let's get back to the question how `clj` tool can make use of these toys. Clj comes with a nice mechanism of `aliases` which allows to specify
at command line additional dependencies or classpaths to be resolved when application starts up. Let's add few aliases to group dependencies based on tools required during development time:
Assuming clojurescript, nrepl and capsule for packaging as base tools being used, this is all we need in `deps.edn`: 

``` clojure
{:aliases {:dev {:extra-paths ["target/assets"]
                 :main-opts   ["-m" "revolt.bootstrap"
                               "-p" "nrepl,rebel"]}

           ;; dependencies for nrepl
           :dev/nrepl {:extra-deps {org.clojure/tools.nrepl {:mvn/version "0.2.13"}
                                    cider/cider-nrepl {:mvn/version "0.18.0-SNAPSHOT"}
                                    refactor-nrepl {:mvn/version "2.4.0-SNAPSHOT"}}}

           ;; dependencies for clojurescript builds
           :dev/cljs {:extra-deps {binaryage/devtools {:mvn/version "0.9.9"}
                                   figwheel-sidecar {:mvn/version "0.5.15"}}}

           ;; dependencies for packaging tasks
           :dev/pack {:extra-deps {co.paralleluniverse/capsule {:mvn/version "1.0.3"}
                                   co.paralleluniverse/capsule-maven {:mvn/version "1.0.3"}}}}}
```

Note the `:extra-paths` and `:main-opts`. First one declares additional class path - a _target/assets_ directory where certain tasks (eg. sass, cljs, aot) generate their assets like CSSes or compiled clojurescripts.
This is required to keep things auto-reloadable - application needs to be aware of resources being regenerated.

`:main-opts` on the other hand are the parameters that `clj` will use to bootstrap revolt: `-m revolt.bootstrap` instructs `clj` to use `revolt.bootstrap` namespace as a main class and pass rest of parameters over there. 

Here is the list of all accepted parameters:

    -c, --config     : location of configuration resource. Defaults to "revolt.edn".

    -d, --target-dir : location of target directory (relative to project location). This is where all re/generated
                       assets are being stored. Defaults to "target".

    -p, --plugins    : comma separated list of plugins to activate. Each plugin (a stringified keyword) may be
                       specified with optional parameters:
    
                          clojure -A:dev:dev/nrepl:dev/cljs:dev/pack -p revolt.task/nrepl,revolt.task/rebel:init-ns=revolt.task
                      
    -t, --tasks      : comma separated list of tasks to run. Simmilar to --plugins, each task (a stringified keyword)
                       may be specified with optional parameters:
    
                          clojure -A:dev:dev/nrepl:dev/cljs:dev/pack -t revolt.plugin/clean,revolt.plugin/info:env=test:version=1.1.2
                      

To make things even easier to type, namespace part of keyword may be omitted when a built-in task or plugin is being used. So, it's perfectly legal to call something like this:

                          clojure -A:dev:dev/nrepl:dev/cljs:dev/pack -t clean,info:env=test:version=1.1.2


## Development and more tech details

This is to describe in details of how plugins and task are loaded and initialized and provide a simple guide to develop own extensions.

### Plugins rediscovered

When _revolt_ starts up, it collects all the keywords listed in `--plugins` and sequentially loads corresponding namespaces calling a `create-plugin` multi-method at the end (with keywords themselves as a dispatch values). Every such a function returns an object which extends a `Plugin` protocol:

```clojure
    (defprotocol Plugin
      (activate [this ctx] "Activates plugin within given context")
      (deactivate [this ret] "Deactivates plugin"))
```

This simple mechanism allows to create a new plugin just like that:

```clojure
    (ns defunkt.foo
      (:require [revolt.plugin :refer [Plugin create-plugin]]))
      
    (defmulti create-plugin ::bar [_ config]
      (reify Plugin
        (activate [this ctx] ...)
        (deactivate [this ret] ...)))
```

And configure it later as follows:
    
    {:defunkt.foo/bar {:bazz 1}}
    
Each plugin gets a _context_ during activation phase. Context contains all the crucial stuff that most of plugins base on:

```clojure
    (defprotocol PluginContext
      (classpaths [this]   "Returns project classpaths.")
      (target-dir [this]   "Returns a project target directory.")
      (config-val [this k] "Returns a value from configuration map."))
```

Note that plugin activation should return a value required to its correct deactivation. This value will be passed later to `deactivate` function as `ret`.

