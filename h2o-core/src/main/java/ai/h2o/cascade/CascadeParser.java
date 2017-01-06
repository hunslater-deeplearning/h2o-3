package ai.h2o.cascade;

import ai.h2o.cascade.asts.*;
import water.util.CollectionUtils;
import water.util.StringUtils;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;


/**
 * This class handles parsing of a Cascade expression into a Cascade AST.
 * Main language structures are the following:
 *
 * <dl>
 *   <dt><p>{@code (fun val1 ... valN)}</dt>
 *   <dd>Function {@code fun} applied to the provided list of values. Any
 *       expression surrounded in parentheses is considered a function
 *       application, with first token being interpreted as the function
 *       itself, and subsequent tokens as the function's arguments.</dd>
 *
 *   <dt><p>{@code -42.7e+03}</dt>
 *   <dd>Number literal. In addition to standard floating-point literals we
 *       recognize strings {@code "nan"} and {@code "NaN"} as representing
 *       a Not-a-Number value. We do not recognize literals corresponding to
 *       infinite double values.</dd>
 *
 *   <dt><p>{@code [7 4.2 nan -1.78E+3]}</dt>
 *   <dd>List of numbers. The elements of the list may be separated either with
 *       spaces (canonical) or with commas (for convenience).</dd>
 *
 *   <dt><p>{@code "Hello, \"world\"!"}</dt>
 *   <dd>String literal, may be enclosed either in single or in double quotes.
 *       Standard C-style escapes are supported: {@code "\n", "\t", "\r", "\f",
 *       "\b", "\'", "\"", "\\", "\xAA", "\u005Cu1234", "\U0010FFFF"}. Only
 *       {@code "\"} character and quotes have to be escaped, escaping other
 *       characters is optional. For example, you can use a string literal with
 *       newline characters, or ASCII control characters, or Unicode. However
 *       such usage may be outlawed in the future.</dd>
 *
 *   <dt><p>{@code ['one' "two" "thre\u005cu0207"]}</dt>
 *   <dd>List of strings.</dd>
 *
 *   <dt><p>{@code <1:2:-1 3:3 5 7>}</dt>
 *   <dd>Slice list: special notation for writing ranges of numbers compactly.
 *       Individual items within this list are either single numbers, or
 *       pairs {@code base:count}, or triples {@code base:count:stride}. When
 *       either count or stride are not given, they are assumed to be 1.<br/>
 *       Each triple corresponds to the sequence of numbers {@code [base,
 *       base + stride, ..., base + (count-1)*stride]}.<br/>
 *       Within this list only integers (long) are allowed. Additionally,
 *       {@code count}s must be positive, while {@code base}s and
 *       {@code stride}s may be positive, negative, or zero.</dd>
 *
 *   <dt><p>{@code `var1 var2 ... varN *argvars`}</dt>
 *   <dd>List of unevaluated identifiers.</dd>
 *
 *   <dt><p>{@code ?expr}</dt>
 *   <dd>Unevaluated expression. Putting a question mark before an expression
 *       indicates that it should be passed unevaluated onto the execution
 *       stack. This constract is necessary for some functions that do not
 *       evaluate their arguments in the standard manner: {@code if, for, def,
 *       and, or}, etc. For example, an {@code if} statement should be written
 *       as {@code (if test ?then ?else)}, where {@code test} is a boolean
 *       condition and {@code then} and {@code else} are subexpressions that
 *       should be executed when the test is true / false respectively. Without
 *       the question marks all 3 arguments would have been evaluated before
 *       being passed to {@code if}. With question marks, only {@code test} is
 *       evaluted, and then the {@code if} function determines whether it needs
 *       to execute {@code then} or {@code else} subexpressions.</dd>
 * </dl>
 *
 */
public class CascadeParser {
  private String expr;  // Statement to parse and then execute
  private int pos;      // Parse pointer, points to the index of the next character to be consumed


  public CascadeParser(String expresssion) {
    expr = expresssion;
    pos = 0;
  }

  /**
   * Parse a Cascade expression string into an AST object.
   *
   * @throws Cascade.SyntaxError if the expression cannot be parsed.
   */
  public AstNode parse() throws Cascade.SyntaxError {
    AstNode res = parseNext();
    if (nextChar() != ' ')
      throw syntaxError("Illegal Cascade expression");
    return res;
  }


  //--------------------------------------------------------------------------------------------------------------------
  // Private
  //--------------------------------------------------------------------------------------------------------------------

  // Set of characters that may appear in a number. Note that "NaN" or "nan" is also a number.
  private static Set<Character> validNumberCharacters = StringUtils.toCharacterSet("0123456789.-+eEnNaA");

  // List of all "simple" backslash-escape sequences (i.e. those that are only 2-characters long, e.g. '\n')
  private static Map<Character, Character> simpleEscapeSequences =
      CollectionUtils.createMap(StringUtils.toCharacterArray("ntrfb'\"\\"),
                                StringUtils.toCharacterArray("\n\t\r\f\b'\"\\"));


  /**
   * Parse and return the next AST from the rapids expression string.
   */
  private AstNode parseNext() {
    char ch = nextChar();
    if (ch == '(') {
      return parseFunctionApplication();
    }
    if (ch == '[') {
      return parseList();
    }
    if (ch == '`') {
      return parseIdList();
    }
    if (ch == '<') {
      return parseSliceList();
    }
    if (ch == '?') {
      return parseUnevaluatedExpr();
    }
    if (isQuote(ch)) {
      return new AstStr(parseString());
    }
    if (isDigit(ch) || ch == '-' || ch == '.') {
      return new AstNum(parseDouble());
    }
    if (isAlpha(ch)) {
      String id = parseId();
      if (id.toLowerCase().equals("nan")) return new AstNum(Double.NaN);
      return new AstId(id);
    }
    throw syntaxError("Invalid syntax");
  }


  /**
   * Parse "function application" expression, i.e. construct of the form
   * {@code (func arg1 ... argN)}.
   */
  private AstApply parseFunctionApplication() {
    consumeChar('(');
    int start = pos;
    AstNode head = parseNext();
    head.setPos(start, pos);

    ArrayList<AstNode> args = new ArrayList<>();
    while (nextChar() != ')') {
      int argStart = pos;
      AstNode ast = parseNext();
      ast.setPos(argStart, pos);
      args.add(ast);
    }
    consumeChar(')');
    AstApply res = new AstApply(head, args);
    res.setPos(start - 1, pos);
    return res;
  }


  /**
   * Parse and return a list of tokens: either a list of strings, or a list
   * of numbers. We do not support lists of mixed types, or lists containing
   * variables / other expressions. If necessary, such lists can always be
   * created using the {@code list} function.
   */
  private AstNode parseList() {
    consumeChar('[');
    char nextChar = nextChar();
    AstNode res = isQuote(nextChar)? parseStringList() : parseNumList();
    consumeChar(']');
    return res;
  }


  /**
   * Parse a list of strings. Strings can be either in single- or in double
   * quotes. Additionally we allow elements to be either space- or comma-
   * separated.
   */
  private AstStrList parseStringList() {
    ArrayList<String> strs = new ArrayList<>(10);
    while (nextChar() != ']') {
      strs.add(parseString());
      if (nextChar() == ',') consumeChar(',');
      if (nextChar() == ' ') throw syntaxError("Unexpected end of string");
    }
    return new AstStrList(strs);
  }

  /**
   * Parse a plain list of numbers.
   */
  private AstNode parseNumList() {
    ArrayList<Double> nums = new ArrayList<>(10);
    while (nextChar() != ']') {
      nums.add(parseDouble());
      if (nextChar() == ',') consumeChar(',');
      if (nextChar() == ' ') throw syntaxError("Unexpected end of string");
    }
    return new AstNumList(nums);
  }


  /**
   * Parse a list of numbers with slices/ranges. For example:
   * <pre>{@code
   *   <0, -3, 2:7:5, 3:2, -5:11:-2>
   * }</pre>
   * The format of each "range" token is {@code start:count[:stride]}, and it
   * denotes the sequence (where {@code stride=1} if not given)
   * <pre>{@code
   *   (start, start + stride, ..., start + (count-1)*stride)
   * }</pre>
   * Real numbers cannot be used in this list format. Within each range token
   * {@code count} must be positive, whereas {@code stride} can be either
   * positive or negative.
   *
   * <p> Primary use for this number list is to support indexing into columns/
   * rows of a frame.
   */
  private AstSliceList parseSliceList() {
    consumeChar('<');
    ArrayList<Long> bases = new ArrayList<>(5);
    ArrayList<Long> counts = new ArrayList<>(5);
    ArrayList<Long> strides = new ArrayList<>(5);

    while (nextChar() != '>') {
      long base = parseLong();
      long count = 1;
      long stride = 1;
      if (nextChar() == ':') {
        consumeChar(':');
        count = parseLong();
        if (count <= 0)
          throw syntaxError("Count must be a positive integer");
      }
      if (nextChar() == ':') {
        consumeChar(':');
        stride = parseLong();
      }
      // If count is 1 then stride is irrelevant, so we force it to be 1 as well.
      if (count == 1) stride = 1;
      bases.add(base);
      counts.add(count);
      strides.add(stride);
      // Optional comma separating list elements
      if (nextChar() == ',') consumeChar(',');
    }
    consumeChar('>');
    return new AstSliceList(bases, counts, strides);
  }


  /**
   * Parse list of identifiers that will be kept unevaluated. This list takes
   * the form
   * {@code  `var1 var2 ... varN`}
   */
  private AstIdList parseIdList() {
    consumeChar('`');
    ArrayList<String> ids = new ArrayList<>(10);
    String argsId = null;
    while (nextChar() != '`') {
      if (nextChar() == '*') {
        consumeChar('*');
        argsId = parseId();
      } else {
        if (argsId != null)
          throw syntaxError("regular variable cannot follow a vararg variable");
        ids.add(parseId());
      }
      if (nextChar() == ',') consumeChar(',');
    }
    consumeChar('`');
    return new AstIdList(ids, argsId);
  }


  /**
   * Parse an id from the input stream. An id has common interpretation:
   * an alpha character followed by any number of alphanumeric characters.
   */
  private String parseId() {
    int start = pos;
    while (isAlphaNum(peek())) pos++;
    assert pos > start;
    return expr.substring(start, pos);
  }


  /**
   * Parse a number from the token stream.
   */
  private double parseDouble() {
    int start = pos;
    while (validNumberCharacters.contains(peek())) pos++;
    if (start == pos) throw syntaxError("Expected a number");
    String s = expr.substring(start, pos);
    if (s.toLowerCase().equals("nan")) return Double.NaN;
    try {
      return Double.valueOf(s);
    } catch (NumberFormatException e) {
      String msg = e.getMessage();
      throw syntaxError(msg.startsWith("For input string:")? "Invalid number" : "Invalid number: " + msg, start);
    }
  }


  /**
   * Parse a (long) integer from the token stream.
   */
  private long parseLong() {
    nextChar();
    int start = pos;
    if (peek() == '-') pos++;
    while (isDigit(peek())) pos++;
    if (start == pos) throw syntaxError("Missing a number");
    String s = expr.substring(start, pos);
    try {
      return Long.parseLong(s);
    } catch (NumberFormatException e) {
      throw syntaxError(e.toString());
    }
  }


  /**
   * Parse a string from the token stream.
   */
  private String parseString() {
    char quote = peek();
    if (!isQuote(quote)) {
      throw syntaxError("Expected a string");
    }
    int start = ++pos;
    boolean has_escapes = false;
    while (pos < expr.length()) {
      char c = peek();
      if (c == '\\') {
        has_escapes = true;
        int escapeStart = pos;
        consumeChar('\\');
        char cc = peek();
        if (simpleEscapeSequences.containsKey(cc)) {
          pos += 1;
        } else if (cc == 'x') {
          pos += 3;   // e.g: \x5A
        } else if (cc == 'u') {
          pos += 5;   // e.g: \u1234
        } else if (cc == 'U') {
          pos += 9;   // e.g: \U0010FFFF
        } else
          throw syntaxError("Invalid escape sequence", pos++ - 1);
        if (pos > expr.length()) {
          pos = expr.length();
          throw syntaxError("Escape sequence too short", escapeStart);
        }
        if (pos > escapeStart + 2) {
          for (int i = escapeStart + 2; i < pos; i++) {
            char ch = expr.charAt(i);
            if (!isHexDigit(ch))
              throw syntaxError("Escape sequence contains non-hexademical character '" + ch + "'", escapeStart);
          }
        }
      } else if (c == quote) {
        pos++;
        // End of string -- now either unescape it or return as-is
        if (has_escapes) {
          StringBuilder sb = new StringBuilder();
          for (int i = start; i < pos - 1; i++) {
            char ch = expr.charAt(i);
            if (ch == '\\') {
              char cc = expr.charAt(++i);
              if (simpleEscapeSequences.containsKey(cc)) {
                sb.append(simpleEscapeSequences.get(cc));
              } else {
                int n = (cc == 'x')? 2 : (cc == 'u')? 4 : (cc == 'U')? 8 : -1;
                String hexStr = expr.substring(i + 1, i + 1 + n);
                int hex;
                try {
                  hex = StringUtils.unhex(hexStr);
                } catch (NumberFormatException e) {
                  throw syntaxError(e.toString());
                }
                if (hex > 0x10FFFF) {
                  pos = i + n + 1;
                  throw syntaxError("Illegal Unicode codepoint 0x" + hexStr.toUpperCase(), i - 1);
                }
                sb.append(Character.toChars(hex));
                i += n;
              }
            } else {
              sb.append(ch);
            }
          }
          return sb.toString();
        } else {
          return expr.substring(start, pos - 1);
        }
      } else {
        pos++;
      }
    }
    throw syntaxError("Unterminated string", start - 1);
  }


  private AstNode parseUnevaluatedExpr() {
    consumeChar('?');
    AstNode next = parseNext();
    return new AstUneval(next);
  }


  //--------------------------------------------------------------------------------------------------------------------
  // (Private) helpers
  //--------------------------------------------------------------------------------------------------------------------

  /**
   * Return a character at the current parse position without advancing it.
   * If current position is at the end of the input, return a space.
   */
  private char peek() {
    return pos < expr.length()? expr.charAt(pos) : ' ';
  }

  /**
   * Advance parse pointer to the first non-whitespace character, and return
   * that character. If all remaining input characters are whitespace, then
   * advance parse position to the end of the input and return a space.
   */
  private char nextChar() {
    while (pos < expr.length() && isWhitespace(expr.charAt(pos))) pos++;
    return peek();
  }

  /**
   * Consume the next character from the parse stream, throwing an exception
   * if it is not {@code c}.
   */
  private void consumeChar(char c) {
    if (peek() != c)
      throw syntaxError("Expected '" + c + "'. Got: '" + peek());
    pos++;
  }

  /** Return true if {@code c} is a whitespace character. */
  private static boolean isWhitespace(char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\r';
  }

  /** Return true if {@code c} is a quote character. */
  private static boolean isQuote(char c) {
    return c == '\'' || c == '\"';
  }

  /** Return true if character {@code c} is a letter (or _). */
  private static boolean isAlpha(char c) {
    return c >= 'a' && c <= 'z' || c == '_' || c >= 'A' && c <= 'Z';
  }

  /** Return true if character {@code c} is a digit. */
  private static boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }

  /** Return true if {@code c} is an alphanumeric character. */
  private static boolean isAlphaNum(char c) {
    return isAlpha(c) || isDigit(c);
  }

  /** Return true if character {@code c} is a hexdemical digit. */
  private static boolean isHexDigit(char c) {
    return c >= '0' && c <= '9' || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
  }


  /**
   *
   * Usage: {@code throw syntaxError("error message")}.
   */
  private Cascade.SyntaxError syntaxError(String message) {
    return new Cascade.SyntaxError(message, pos);
  }

  private Cascade.SyntaxError syntaxError(String message, int start) {
    return new Cascade.SyntaxError(message, start, pos - start);
  }

}