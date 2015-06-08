(ns gabo.core)

(defmacro when-match
  "Syntax:
  (when-match string
    [sym1 regex1] form1
     ...
    [symN regexN] formN)

  Usage: matches string to every regex, if there is a match then
  the form will be evaluated and returned.
  "
  [string & forms]
  (cons `cond
        (loop [form-pairs (partition 2 forms)
               result []]
          (if (empty? form-pairs)
            result
            (let [[[sym regex] body] (first form-pairs)]
              (recur (rest form-pairs)
                     (conj result
                           `(re-find ~regex ~string)
                           `(let [~sym (re-find ~regex ~string)]
                              ~body))))))))

(defn tokenize-chunk
  "Given a string of code return a tuple of [match token]
  where match is the exact string that matched and token is
  the token that corresponds to the regex match.
  Example: (tokenize-chunk '{{bar}} foo') will return
  ['{{bar}}' [:symbol 'bar']]"
  [code]
  (when-match code
    [sym #"\A\{\{\s*(\w+)\s*\}\}"] [ (first sym) [:symbol (last sym) ] ]
    [iter-end #"\A\{\{/\s*(\w+)\s*\}\}"] [ (first iter-end) [:iter-end (last iter-end) ]]
    [iter-init #"\A\{\{#\s*(\w+)\s*\}\}"] [ (first iter-init) [:iter-init (last iter-init) :default ]]
    [iter-init #"\A\{\{#\s*(\w+)\s+(\S+?)\s*\}\}"] [(first iter-init) (cons :iter-init (rest iter-init))]
    [literal #"\A([\s\S][\s\S]*?)\{\{"] [(last literal) [:literal (last literal)]]
    [literal #"\A[\s\S]*"] [literal [:literal literal]]))

(defn tokenize
  [code]
  (loop [code code
         tokens []]
    (if (empty? code)
      tokens
      (let [[str-match token] (tokenize-chunk code)]
        (recur (.substring code (count str-match))
               (conj tokens token))))))

(defn is-token [token token-type]
  (= (first token) token-type))

(defn is-literal
  "Returns true if the given token is a :literal,
  false otherwise."
  [token] (is-token token :literal))

(defn is-symbol
  "Returns true if the given token is a :symbol,
  false otherwise."
  [token] (is-token token :symbol))

(defn is-iter-init
  [token] (is-token token :iter-init))

(defn is-iter-end
  [token] (is-token token :iter-end))

(defn merge-literals
  "merges two literal tokens into one"
  [token1 token2]
  (vector :literal
          (str (last token1)
               (last token2))))

(defn join-literals
  "Given a collection of tokens, this function
  will return a collection of tokens where all
  :literal consequent tokens will be merged"
  [tokens]
  (loop [tokens tokens
         result []]
    (cond
      (empty? tokens) result
      (and (is-literal (first tokens))
           (is-literal (last result)))
        (recur (rest tokens)
               (conj (pop result) (merge-literals (last result)
                                                  (first tokens))))
      :else
        (recur (rest tokens)
               (conj result (first tokens))))))

(defn mutable-list
  ([] (java.util.ArrayList.))
  ([xs]
   (let [result (mutable-list)]
     (doseq [x xs]
       (.add result x))
     result)))


(defn add [mutable-list element]
  (.add mutable-list element)
  mutable-list)

(defn build-ast
  [tokens]
  ;; NOTE: until I can figure out how to implement this
  ;; using persistent data structured, good ol' java will
  ;; have to do.
  (let [ast (mutable-list)
        stack (java.util.Stack.)]
    (.push stack ast) ; initialize the stack
    (doseq [token tokens]
      (cond
        (or (is-literal token) (is-symbol token))
          (add (.peek stack) token)
        (is-iter-init token)
          (let [mutable-token (mutable-list [:iter (second token) (last token)])]
            (add mutable-token (mutable-list))
            (add (.peek stack) mutable-token)
            (.push stack (last mutable-token)))
        (is-iter-end token)
          (.pop stack)))
    ast))

(defn parse
  [string]
  ((comp build-ast
         join-literals
         tokenize) string))

