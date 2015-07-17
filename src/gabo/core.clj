(ns gabo.core
  (:require [gabo.util :refer :all])
  (:import (java.util ArrayList Stack)))

;; execute define-is-token-funcs to actually define the given functions:
;; is-literal, is-symbol, etc.
(define-is-token-funcs :literal :symbol :iter-init :iter-end :iter)

(defn tokenize-chunk
  "Given a string of code return a tuple of [match token]
  where match is the exact string that matched and token is
  the token that corresponds to the regex match.
  Example: (tokenize-chunk '{{bar}} foo') will return
  ['{{bar}}' [:symbol 'bar']]"
  [code]
  (when-match code
    [sym #"\A\{\{\s*([\w-\.]+)\s*\}\}"] [ (first sym) [:symbol (last sym) ] ]
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

(defn add! [array-list element]
  (.add array-list element)
  array-list)

(defn persist-tree
  "Given a mutable list of nodes, converts it to a immutable tree"
  [tree]
  (cond
    (or (is-literal tree) (is-symbol tree))
      (vec tree)
    (is-iter tree)
      (let [[token-type sym separator sub-tree] tree]
        (vector token-type sym separator (map persist-tree sub-tree)))
    :else
      (map persist-tree tree)))


(defn build-ast
  [tokens]
  ;; NOTE: until I can figure out how to implement this
  ;; using persistent data structured, good ol' java will
  ;; have to do.
  (let [ast (ArrayList.)
        stack (java.util.Stack.)]
    (.push stack ast) ; initialize the stack
    (doseq [token tokens]
      (cond
        (or (is-literal token) (is-symbol token))
          (add! (.peek stack) token)
        (is-iter-init token)
          (let [mutable-token (ArrayList. [:iter (second token) (last token)])]
            (add! mutable-token (ArrayList.))
            (add! (.peek stack) mutable-token)
            (.push stack (last mutable-token)))
        (is-iter-end token)
          (.pop stack)))
    ast))

(defn parse
  "Parses a template string and returns a compiled tree representation of the template.
  You can later use (eval-tree tree context) to render a compiled template with a given
  context."
  [string]
  ((comp persist-tree
         build-ast
         tokenize) string))

(defn eval-tree
  "Evaluates a compiled template as a tree with the given context"
  [tree ctx]
  (cond (is-literal tree)
          (second tree)
        (is-symbol tree)
          (let [[_ sym] tree]
            (if (= sym ".")
              (str ctx)
              (get ctx (keyword sym) "")))
        (is-iter tree)
          (apply str
                 (let [[_ sym separator sub-tree] tree ]
                   (interpose (if (= :default separator) "," separator)
                              (for [item (ctx (keyword sym))]
                                (eval-tree sub-tree item)))))
        (coll? tree)
          (apply str (map #(eval-tree % ctx) tree))))

(defn render
  "Compiles and evaluates the template with the given context"
  [template ctx]
  (eval-tree (parse template) ctx))
