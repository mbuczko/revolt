(set-env!
 :source-paths #{"src"}
 :resource-paths #{"resources"}
 :dependencies '[[org.clojure/java.classpath "0.3.0"]
                 [org.clojure/tools.cli "0.4.2"]
                 [org.clojure/tools.logging "0.5.0-alpha.1"]
                 [org.clojure/tools.namespace "0.3.0-alpha4"]
                 [org.clojure/tools.deps.alpha "0.6.496" :exclusions [org.slf4j/slf4j-nop]]
                 [com.bhauman/rebel-readline "0.1.4"]
                 [net.sf.jpathwatch/jpathwatch "0.95"]
                 [io.aviso/pretty "0.1.37"]
                 [deraen/sass4clj "0.3.1"]
                 [metosin/bat-test "0.4.2"]
                 [codox "0.10.6"]
                 [eftest "0.5.7"]
                 [javazoom/jlayer "1.0.1"]
                 [adzerk/bootlaces "0.2.0" :scope "test"]
                 [ch.qos.logback/logback-classic "1.2.3" :scope "provided"]])

;; to check the newest versions:
;; boot -d boot-deps ancient

(def +version+ "1.2.0")

(require
 '[clojure.tools.namespace.repl]
 '[adzerk.bootlaces :refer :all])

(bootlaces! +version+)

;; which source dirs should be monitored for changes when resetting app?
(apply clojure.tools.namespace.repl/set-refresh-dirs (get-env :source-paths))

(task-options!
 pom {:project 'defunkt/revolt
      :version +version+
      :description "Trampoline to Clojure dev toolbox"
      :url "https://github.com/mbuczko/revolt"
      :scm {:url "https://github.com/mbuczko/revolt"}})
