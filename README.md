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
- [x] test - clojure.test runner based on Metosin's [bat-test](https://github.com/metosin/bat-test)
- [x] info - project info (name, description, package, version, git sha...)
- [x] codox - API documentation with [codox](https://github.com/weavejester/codox)
- [ ] lint - linter based on [eastwood](https://github.com/jonase/eastwood)
- [ ] uberjar - [onejar](http://one-jar.sourceforge.net/) packaging from JUXT's [pack.alpha](https://github.com/juxt/pack.alpha)
- [ ] capsule - [capsule](http://www.capsule.io) packaging from JUXT's [pack.alpha](https://github.com/juxt/pack.alpha)
- [ ] ancient - looking for outdated dependencies

## Plugins

Plugins are one these guys who always cause problems. No matter if that's boot or lein, they just barely fit into architecture with what they do. And they do a lot of
weird things, eg. nREPL is a socket server waiting for connection, REPL is a command line waiting for input, watcher on the other hand is a never ending loop watching
for file changes. Apples and oranges put together into same basket.

_Revolt_ does not try to unify them and pretend they're same tasks as cljs compilation or scss transformation. They are simply a piece of code which starts when asked and
stops working when JVM shuts down. Nothing more than that. Technically, plugins (as well as tasks) are identified by qualified keyword and configured in a separate file.
Typical configuration looks like following:

```clojure
    {:revolt.plugin/nrepl {:port 5600}
     :revolt.plugin/rebel {:init-ns "codocs.system"}
     :revolt.plugin/watch {:excluded-paths ["src/clj" "resources"]
                           :on-change {:revolt.task/sass "glob:**/assets/styles/*.scss"}}}
```

When _revolt_ starts up, it collects all the required keywords (more on that later) and sequentially tries to load corresponding namespaces and finally call a `create-plugin`
multi-method with keywords themselves as a dispatch values. Every such a function returns an object which extends a `Plugin` protocol:

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
    (defprotocol Context
      (classpaths [this]   "Returns project classpaths.")
      (target-dir [this]   "Returns a project target directory.")
      (config-val [this k] "Returns a value from configuration map.")
      (terminate  [this]   "A function which sends a signal to deactivate all plugins."))
```

Some of plugins have no dependencies (eg. _nrepl_ or _rebel_), some depend on specific tasks, eg. _figwheel_ plugin depends
on _cljs_ task and its own configuration. Each task gets initialized at plugin activatation run-time, when required.

Note that plugin activation should return a value required to its correct deactivation. This value will be passed later to `deactivate` 
function as `ret`.

## Tasks

## Usage

## Development
