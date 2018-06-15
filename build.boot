(set-env!
 :source-paths   #{"src"}
 :dependencies   '[[org.clojure/clojure "1.9.0"]
                   [org.clojure/tools.cli "0.3.5"]
                   [org.clojure/tools.logging "0.4.1"]
                   [org.clojure/java.classpath "0.2.3"]
                   [org.clojure/tools.deps.alpha "0.5.435" :exclusions [org.slf4j/slf4j-nop]]
                   [com.bhauman/rebel-readline "0.1.3"]
                   [net.sf.jpathwatch/jpathwatch "0.95"]
                   [io.aviso/pretty "0.1.34"]
                   [deraen/sass4clj "0.3.1"]
                   [metosin/bat-test "0.4.0"]
                   [codox "0.10.3"]
                   [eftest "0.4.3"]
                   [adzerk/bootlaces "0.1.13" :scope "test"]
                   [ch.qos.logback/logback-classic "1.2.3" :scope "provided"]])

;; to check the newest versions:
;; boot -d boot-deps ancient

(def +version+ "0.1.4")

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
