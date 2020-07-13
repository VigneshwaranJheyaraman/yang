(ns yang.lang
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as s]
            [clojure.pprint :as pp])
  (:import [java.util UUID]
           [java.io ByteArrayOutputStream ByteArrayInputStream Reader]
           [java.util.zip GZIPOutputStream GZIPInputStream]
           [java.util.concurrent Future CompletableFuture]
           [java.util.function Supplier Function]
           [clojure.lang Reflector]))

(defonce zero-uuid (UUID. 0 0))

(defn suuid? [s]
  (try (UUID/fromString s)
       true
       (catch Exception e)))

(defn uuid []
  (UUID/randomUUID))

(defn str->uuid [s]
  (when (seq s)
    (UUID/fromString s)))

(defn fmv
  "apply f to each value v of map m"
  [m f]
  (into {}
        (for [[k v] m]
          [k (f v)])))

(defn fmk
  "apply f to each key k of map m"
  [m f]
  (into {}
        (for [[k v] m]
          [(f k) v])))

(defn jcoll? [x]
  (instance? java.util.Collection x))

(defn str=
  "case insensitive"
  [s v]
  (= (s/lower-case (or s ""))
     v))

(defn str-val [v]
  (if v
    (str v)
    ""))

(defn trim [s]
  (if (string? s)
    (s/trim s)))

(defn to-long [n]
  (if (number? n)
    (long n)))

(defn to-double [n]
  (if (number? n)
    (double n)))

(defn parse-long [l]
  (try (Long/valueOf l)
       (catch Exception e)))

(defn parse-double [d]
  (try (Double/valueOf d)
       (catch Exception e)))

(defn parse-number [n]
  (let [s (str n)]
    (if (re-find #"^-?\d+\.?\d*$" s)
      (edn/read-string s))))

(defn ranged-rand [start end]
  (+ start (long (rand (- end start)))))

(defn plus [& xs]
  (apply + (remove nil? xs)))

(defn value? [v]
  (or (number? v)
      (seq v)))

(defn value [v]
  (when (value? v)
    v))

(defn none? [v]
  (cond
    (= v "") true
    (coll? v) (empty? v)
    (nil? v) true))

(defn one-if-none [n]
  (if (or (not n)
          (zero? n))
    1
    n))

(defn remove-key-ns
  ([m]
   (fmk m name))
  ([m kns]
  (->> (filter (fn [[k v]] (not= (namespace k)
                                 (name kns))) m)
       (into {}))))

(defn extract-key-ns
  "=> (extract-key-ns {:a/one :a-one :a/two :a-two :b/one :b-one :b/two :b-two}
                      :a)

      {:a {:one :a-one
           :two :a-two}}"
  [m kns]
  {kns (reduce-kv (fn [a k v]
                    (if (= (namespace k)
                           (name kns))
                      (assoc a (-> k name keyword) ;; :foo/bar -> :bar
                             v)
                      a))
                  {}
                  m)})

(defn replace-in-kw
  "=> (replace-in-kw :foo-bar-baz \"bar\" \"zoo\")
      :foo-zoo-baz"
  [kw from to]
  (-> kw
      name
      (s/replace from to)
      keyword))

(defn replace-in-keys [m from to]
  (into {}
        (for [[k v] m]
          [(replace-in-kw k from to) v])))

(defn replace-line [path from to]
  (->> (slurp path)
       s/split-lines
       (map #(s/replace-first % from to))
       (s/join "\n")
       (spit path)))

(defn dash-keys
  ([m]
   (dash-keys "_" m))
  ([from m]
   (replace-in-keys m from "-")))

(defn underscore-keys
  ([m]
   (underscore-keys "-" m))
  ([from m]
   (replace-in-keys m from "_")))

(defn dash-to-camel [xs]
  (s/replace xs #"-(\w)"
             #(s/upper-case (second %))))

(defn camel-to-dash [k]
  (->> (map s/lower-case
            (s/split (name k) #"(?=[A-Z])"))
    (s/join "-")
    keyword))

(defn and->
  ([] true)
  ([x] x)
  ([x & [f & fs]]
   (let [r (f x)]
     (if (and r (seq fs))
       (apply and-> x fs)
       r))))

(defn positive? [xs]
  (every? #(and-> % number? pos?)
          xs))

(defn pos-number? [n]
  (and n
       (number? n)
       (pos? n)))

(defn capitalize [xs]
  (when (seq xs)
    (->> (s/split xs #" ")
         (map (comp s/capitalize s/lower-case))
         (s/join #" "))))

(defn to-alpha-num [xs]
  (->> (re-seq #"[a-zA-Z0-9]" xs)
       (apply str)))

(defn append-if-absent
  ([v xs]
   (append-if-absent v xs ", "))
  ([v xs delim]
   (if-not (re-find (re-pattern (str "(?i)" ;; case insensitive
                                     (to-alpha-num v))) xs)
     (str xs delim v)
     xs)))

(defn seq->in-params
  ;; convert seqs to IN params: i.e. [1 "2" 3] => "('1','2','3')"
  [xs]
  (as-> xs $
        (map #(str "'" % "'") $)
        (s/join "," $)
        (str "(" $ ")")))

(defn remove-empty
  "=> (remove-empty {:a nil :b \"\" :c [] :d 42})
      {:d 42}"
  [m]
  (into {}
        (for [[k v] m]
          (when-not (none? v)
            [k v]))))

(defn props->map
  "java.util.Properties to map"
  [props]
  (into {}
        (for [[k v] props]
          [(-> k s/trim keyword)
           (s/trim v)])))

(defn ^CompletableFuture future->completable-future [^Future fut pool]
  (CompletableFuture/supplyAsync
    (reify Supplier
      (get [_]
        (try
          (.get fut)
          (catch Exception e
            (throw (ex-info "error in future->completable-future" {} e))))))
    pool))

(defn then-compose [^CompletableFuture cf f]
  (.thenCompose cf (reify Function (apply [_ x] (f x)))))

(defn then-apply [^CompletableFuture cf f]
  (.thenApply cf (reify Function (apply [_ x] (f x)))))

(defn group-by-ns
  "=> (group-by-ns {:a/one :a-one :b/one :b-one :a/two :a-two :b/two :b-two})

      {:a {:one :a-one, :two :a-two}
       :b {:one :b-one, :two :b-two}}"
  [m]
  (-> (group-by (comp namespace key) m)
      (fmk keyword)
      (fmv #(fmk % (comp keyword name)))))

(defn group-by-first
  "takes a seq of 2 element tuples
   and groups them by the first (key) element.
    (group-by-first [[1 2][1 4][2 3][2 7]])
      {1 (4 2), 2 (7 3)}"
  [m]
  (reduce (fn [m [k v]]
            (update m k conj v))
          {}
          m))

(defn deep-merge-with
  "like merge-with, but merges maps recursively, appling the given fn
   only when there's a non-map at a particular level.
   (deepmerge + {:a {:b {:c 1 :d {:x 1 :y 2}} :e 3} :f 4}
                {:a {:b {:c 2 :d {:z 9} :z 3} :e 100}})
   -> {:a {:b {:z 3, :c 3, :d {:z 9, :x 1, :y 2}}, :e 103}, :f 4}"
  [f & maps]
  (apply
    (fn m [& maps]
      (if (every? map? maps)
        (apply merge-with m maps)
        (apply f maps)))
    maps))

(defn merge-maps [& m]
  (apply deep-merge-with (fn [_ v] v) m))

(defn freq-map [m by]
  "frequences but for seq of maps (ignores nil values)
   => (def m [{:a 42, :b 34} {:a 31, :b 10} {:a 42, :c 0} {:a 31, :b 3} {:a 42, :b 5} {:a nil, :b 10} {:a 30, :r 5}])
   => (freq-map m :a)
      {42 3, 31 2, 30 1}"
  (when (seq m)
    (reduce (fn [a e]
              (let [k (e by)]
                (if k
                  (assoc a k (inc (get a k 0)))
                  a)))
            {} m)))

(defn cconj
  "circular conj
   adds an item into a collection, droping the last element
   in case a collection count is greater than a :size.
   (def l '(10 11 12 13 14 15 16 17))
   (take 5 (iterate #(cconj % (rand-int 10) :size 10) l))
   =>
   ((10 11 12 13 14 15 16 17)
    (3 10 11 12 13 14 15 16 17)
    (9 3 10 11 12 13 14 15 16 17)
    (5 9 3 10 11 12 13 14 15 16)
    (2 5 9 3 10 11 12 13 14 15))"
  [xs item & {:keys [size]}]
  (let [c (count xs)
        s (or size c)
        l (-> (take s xs)
              (conj item))]
    (if (>= c s)
      (drop-last l)
      l)))

(defn rm-sort
  "reverse map sort (by key)"
  [m]
  (sort-by first #(compare %2 %1) m))

(defn join [xs1 xs2 on]
  "joins two seqs of maps \"on\" a key:
   (join [{:a 20, :b 34} {:a 31, :b 27} {:a 28, :b 42}]
         [{:a 31, :b 27} {:a 12, :b 4} {:a 28, :b 42}]
         :a)
   => [{:a 31, :b 27} {:a 28, :b 42}]"
  (let [f #(set (map on %))
        inter (clojure.set/intersection (f xs1)
                                        (f xs2))]
    (reduce (fn [a m]
              (if (inter (m on))
                (conj a m)
                a))
            [] xs1)))

(defn csv->vec [csv]
  (when (value? csv)
    (->> (s/split csv #",| ")
         (remove empty?)
         (mapv keyword))))

(defn str->edn [xs]
  (try (edn/read-string xs)
       (catch Exception ex
         (printf "could not parse \"%s\" to EDN due to: %s" xs ex))))

(defn- reader->str [^Reader r]
  (let [^StringBuilder sb (StringBuilder.)]
    (loop [buf (char-array 128) n-read (.read r buf)]
      (when (not= n-read -1)
        (.append sb buf 0 n-read)
        (recur buf (.read r buf))))
    (.toString sb)))

(defn gzip-edn [edn]
  (with-open [out (ByteArrayOutputStream.)
              gzip (GZIPOutputStream. out)]
    (do
      (.write gzip (.getBytes (str edn)))
      (.finish gzip)
      (.toByteArray out))))

(defn gunzip-edn [^bytes bs]
  (with-open [bais (ByteArrayInputStream. bs)
              gis  (GZIPInputStream. bais)]
    (-> gis
        clojure.java.io/reader
        reader->str
        edn/read-string)))

(defn bbuffer->str [bb]
  (when (instance? java.nio.ByteBuffer bb)
    (String. (.array ^java.nio.ByteBuffer bb))))

(defn slurp-resource [path]
  (-> path
      io/resource
      slurp))

(defn edn-resource [path]
  (-> (slurp-resource path)
      edn/read-string))

(defn static-method [clazz method params]
  (Reflector/invokeStaticMethod clazz method (into-array params)))

(defn instance-method
  ([obj method-name]
   (Reflector/invokeInstanceMember obj method-name))
  ([obj method-name args]
   (Reflector/invokeInstanceMethod obj method-name (into-array args))))

(defn private-method [obj method & args]
  (let [m (->> (.. obj getClass getDeclaredMethods)
               (filter #(.. % getName (equals method)))
               first)]
    (. m (setAccessible true))
    (. m (invoke obj args))))

(defn private-field [obj field]
  (let [f (.. obj getClass (getDeclaredField field))]
    (. f (setAccessible true))
    (. f (get obj))))
