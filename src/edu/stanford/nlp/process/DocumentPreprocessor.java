package edu.stanford.nlp.process;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

import edu.stanford.nlp.io.IOUtils;
import edu.stanford.nlp.io.RuntimeIOException;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.ling.HasTag;
import edu.stanford.nlp.ling.HasWord;
import edu.stanford.nlp.ling.Label;
import edu.stanford.nlp.objectbank.XMLBeginEndIterator;

import java.util.function.Function;

import edu.stanford.nlp.util.Generics;
import edu.stanford.nlp.util.PropertiesUtils;
import edu.stanford.nlp.util.StringUtils;

/**
 * Produces a list of sentences from either a plain text or XML document.
 * <p>
 * Tokenization: The default tokenizer is {@link PTBTokenizer}. If null is passed
 * to <code>setTokenizerFactory</code>, then whitespace tokenization is assumed.
 * <p>
 * Adding a new document type requires two steps:
 * <ol>
 * <li> Add a new DocType.
 * <li> Create an iterator for the new DocType and modify the iterator()
 *     function to return the new iterator.
 * </ol>
 * <p>
 * NOTE: This implementation should <em>not</em> use external libraries since it
 * is used in the parser.
 *
 * @author Spence Green
 */
public class DocumentPreprocessor implements Iterable<List<HasWord>> {

  public static enum DocType {Plain, XML}

  public static final String[] DEFAULT_SENTENCE_DELIMS = {".", "?", "!"};

  // inputReader is used in a fairly yucky way at the moment to communicate
  // from a XMLIterator across to a PlainTextIterator.  Maybe redo by making
  // the inner classes static and explicitly passing things around.
  private Reader inputReader;
  private final DocType docType;

  //Configurable options
  private TokenizerFactory<? extends HasWord> tokenizerFactory = PTBTokenizer.coreLabelFactory();
  private String[] sentenceFinalPuncWords = DEFAULT_SENTENCE_DELIMS;
  private Function<List<HasWord>,List<HasWord>> escaper = null;
  private String sentenceDelimiter = null;
  /**
   * Example: if the words are already POS tagged and look like
   * foo_VB, you want to set the tagDelimiter to "_"
   */
  private String tagDelimiter = null;
  /**
   * When doing XML parsing, only accept text in between tags that
   * match this regular expression.  Defaults to everything.
   */
  private String elementDelimiter = ".*";

  private static final Pattern wsPattern = Pattern.compile("\\s+");

  //From PTB conventions
  private final String[] sentenceFinalFollowers = {")", "]", "\"", "\'", "''", "-RRB-", "-RSB-", "-RCB-"};
  
  private boolean keepEmptySentences = false;


  /**
   * Constructs a preprocessor from an existing input stream.
   *
   * @param input An existing reader
   */
  public DocumentPreprocessor(Reader input) {
    this(input,DocType.Plain);
  }

  public DocumentPreprocessor(Reader input, DocType t) {
    if (input == null) {
      throw new IllegalArgumentException("Cannot read from null object!");
    }
    docType = t;
    inputReader = input;
  }

  public DocumentPreprocessor(String docPath) {
    this(docPath, DocType.Plain, "UTF-8");
  }

  public DocumentPreprocessor(String docPath, DocType t) {
    this(docPath, t, "UTF-8");
  }


  /**
   * Constructs a preprocessor from a file at a path, which can be either
   * a filesystem location, a classpath entry, or a URL.
   *
   * @param docPath The path
   * @param encoding The character encoding used by Readers
   */
  public DocumentPreprocessor(String docPath, DocType t, String encoding) {
    if (docPath == null) {
      throw new IllegalArgumentException("Cannot open null document path!");
    }

    docType = t;
    try {
      inputReader = IOUtils.readerFromString(docPath, encoding);
    } catch (IOException ioe) {
      System.err.printf("%s: Could not open path %s\n", this.getClass().getName(), docPath);
      throw new RuntimeIOException(ioe);
    }
  }

  /**
   * Set whether or not the tokenizer keeps empty sentences in
   * whitespace mode.  Useful for programs that want to echo blank
   * lines.  Not relevant for the non-whitespace model.
   */
  public void setKeepEmptySentences(boolean keepEmptySentences) {
    this.keepEmptySentences = keepEmptySentences;
  }

  /**
   * Sets the end-of-sentence delimiters.
   * <p>
   * For newline tokenization, use the argument {"\n"}.
   *
   * @param sentenceFinalPuncWords
   */
  public void setSentenceFinalPuncWords(String[] sentenceFinalPuncWords) {
    this.sentenceFinalPuncWords = sentenceFinalPuncWords;
  }

  /**
   * Sets the factory from which to produce a {@link Tokenizer}.  The default is
   * {@link PTBTokenizer}.
   * <p>
   * NOTE: If a null argument is used, then the document is assumed to be tokenized
   * and DocumentPreprocessor performs no tokenization.
   *
   */
  public void setTokenizerFactory(TokenizerFactory<? extends HasWord> newTokenizerFactory) {
    tokenizerFactory = newTokenizerFactory;
  }

  /**
   * Set an escaper.
   *
   * @param e The escaper
   */
  public void setEscaper(Function<List<HasWord>,List<HasWord>> e) { escaper = e; }

  /**
   * Make the processor assume that the document is already delimited
   * by the supplied parameter.
   *
   * @param s The sentence delimiter
   */
  public void setSentenceDelimiter(String s) { sentenceDelimiter = s; }

  /**
   * Split tags from tokens. The tag will be placed in the TagAnnotation of
   * the returned label.
   * <p>
   * Note that for strings that contain two or more instances of the tag delimiter,
   * the last instance is treated as the split point.
   * <p>
   * The tag delimiter should not contain any characters that must be escaped in a Java
   * regex.
   *
   * @param s POS tag delimiter
   */
  public void setTagDelimiter(String s) { tagDelimiter = s; }

  /**
   * Only read text from inside these XML elements if in XML mode.
   * <i>Note:</i> This class implements an approximation to XML via regex.
   *
   * Otherwise, text will read from all tokens.
   */
  public void setElementDelimiter(String s) { elementDelimiter = s; }


  /**
   * Returns sentences until the document is exhausted. Calls close() if the end of the document
   * is reached. Otherwise, the user is required to close the stream.
   */
  @Override
  public Iterator<List<HasWord>> iterator() {
    // Add new document types here
    if (docType == DocType.Plain) {
      return new PlainTextIterator();
    } else if (docType == DocType.XML) {
      return new XMLIterator();
    } else {
      throw new IllegalStateException("Someone didn't add a handler for a new docType.");
    }
  }


  private class PlainTextIterator implements Iterator<List<HasWord>> {

    private final Tokenizer<? extends HasWord> tokenizer;
    private final Set<String> sentDelims;
    private final Set<String> delimFollowers;
    private Function<String, String[]> splitTag;
    private List<HasWord> nextSent = null;
    private final List<HasWord> nextSentCarryover = Generics.newArrayList();

    public PlainTextIterator() {
      // Establish how to find sentence boundaries
      boolean eolIsSignificant = false;
      sentDelims = Generics.newHashSet();
      if (sentenceDelimiter == null) {
        if (sentenceFinalPuncWords != null) {
          sentDelims.addAll(Arrays.asList(sentenceFinalPuncWords));
        }
        delimFollowers = Generics.newHashSet(Arrays.asList(sentenceFinalFollowers));
      } else {
        sentDelims.add(sentenceDelimiter);
        delimFollowers = Generics.newHashSet();
        eolIsSignificant = wsPattern.matcher(sentenceDelimiter).matches();
        if(eolIsSignificant) { // For Stanford English Tokenizer
          sentDelims.add(PTBLexer.NEWLINE_TOKEN);
        }
      }

      // Setup the tokenizer
      if (tokenizerFactory == null) {
        eolIsSignificant = sentDelims.contains(WhitespaceLexer.NEWLINE);
        tokenizer = WhitespaceTokenizer.
          newWordWhitespaceTokenizer(inputReader, eolIsSignificant);
      } else {
        if (eolIsSignificant) {
          tokenizer = tokenizerFactory.getTokenizer(inputReader, "tokenizeNLs");
        } else {
          tokenizer = tokenizerFactory.getTokenizer(inputReader);
        }
      }

      // If tokens are tagged, then we must split them
      // Note that if the token contains two or more instances of the delimiter, then the last
      // instance is regarded as the split point.
      if (tagDelimiter != null) {
        splitTag = new Function<String,String[]>() {
          private final String splitRegex = String.format("%s(?!.*%s)", tagDelimiter, tagDelimiter);
          public String[] apply(String in) {
            final String[] splits = in.trim().split(splitRegex);
            if(splits.length == 2)
              return splits;
            else {
              String[] oldStr = {in};
              return oldStr;
            }
          }
        };
      }
    }

    private void primeNext() {
      nextSent = Generics.newArrayList(nextSentCarryover);
      nextSentCarryover.clear();
      boolean seenBoundary = false;

      if (!tokenizer.hasNext()) {
        IOUtils.closeIgnoringExceptions(inputReader);
        inputReader = null;
        nextSent = null;
        return;
      }

      do {
        HasWord token = tokenizer.next();
        if (splitTag != null) {
          String[] toks = splitTag.apply(token.word());
          token.setWord(toks[0]);
          if (token instanceof Label) {
            ((Label) token).setValue(toks[0]);
          }
          if(toks.length == 2 && token instanceof HasTag) {
            //wsg2011: Some of the underlying tokenizers return old
            //JavaNLP labels.  We could convert to CoreLabel here, but
            //we choose a conservative implementation....
            ((HasTag) token).setTag(toks[1]);
          }
        }

        if (sentDelims.contains(token.word())) {
          seenBoundary = true;
        } else if (seenBoundary && !delimFollowers.contains(token.word())) {
          nextSentCarryover.add(token);
          break;
        }

        if ( ! (wsPattern.matcher(token.word()).matches() ||
                token.word().equals(PTBLexer.NEWLINE_TOKEN))) {
          nextSent.add(token);
        }

        // If there are no words that can follow a sentence delimiter,
        // then there are two cases.  In one case is we already have a
        // sentence, in which case there is no reason to look at the
        // next token, since that just causes buffering without any
        // chance of the current sentence being extended, since
        // delimFollowers = {}.  In the other case, we have an empty
        // sentence, which at this point means the sentence delimiter
        // was a whitespace token such as \n.  We might as well keep
        // going as if we had never seen anything.
        if (seenBoundary && delimFollowers.size() == 0) {
          if (nextSent.size() > 0 || keepEmptySentences) {
            break;
          } else {
            seenBoundary = false;
          }
        }
      } while (tokenizer.hasNext());

      if (nextSent.size() == 0 && nextSentCarryover.size() == 0 && !keepEmptySentences) {
        IOUtils.closeIgnoringExceptions(inputReader);
        inputReader = null;
        nextSent = null;
      } else if (escaper != null) {
        nextSent = escaper.apply(nextSent);
      }
    }

    public boolean hasNext() {
      if (nextSent == null) {
        primeNext();
      }
      return nextSent != null;
    }

    public List<HasWord> next() {
      if (nextSent == null) {
        primeNext();
      }
      if (nextSent == null) {
        throw new NoSuchElementException();
      }
      List<HasWord> thisIteration = nextSent;
      nextSent = null;
      return thisIteration;
    }

    public void remove() { throw new UnsupportedOperationException(); }
  }


  private class XMLIterator implements Iterator<List<HasWord>> {

    private final XMLBeginEndIterator<String> xmlItr;
    private final Reader originalDocReader;
    private PlainTextIterator plainItr; // = null;
    private List<HasWord> nextSent; // = null;

    public XMLIterator() {
      xmlItr = new XMLBeginEndIterator<String>(inputReader, elementDelimiter);
      originalDocReader = inputReader;
      primeNext();
    }

    private void primeNext() {
      // It is necessary to loop because if a document has a pattern
      // that goes: <tag></tag> the xmlItr will return an empty
      // string, which the plainItr will process to null.  If we
      // didn't loop to find the next tag, the iterator would stop.
      do {
        if (plainItr != null && plainItr.hasNext()) {
          nextSent = plainItr.next();
        } else if (xmlItr.hasNext()) {
          String block = xmlItr.next();
          inputReader = new BufferedReader(new StringReader(block));
          plainItr = new PlainTextIterator();
          if (plainItr.hasNext()) {
            nextSent = plainItr.next();
          } else {
            nextSent = null;
          }
        } else {
          IOUtils.closeIgnoringExceptions(originalDocReader);
          nextSent = null;
          break;
        }
      } while (nextSent == null);
    }

    public boolean hasNext() {
      return nextSent != null;
    }

    public List<HasWord> next() {
      if (nextSent == null) {
        throw new NoSuchElementException();
      }
      List<HasWord> thisSentence = nextSent;
      primeNext();
      return thisSentence;
    }

    public void remove() { throw new UnsupportedOperationException(); }
  } // end class XMLIterator


  private static String usage() {
    StringBuilder sb = new StringBuilder();
    String nl = System.getProperty("line.separator");
    sb.append(String.format("Usage: java %s [OPTIONS] [file] [< file]%n%n", DocumentPreprocessor.class.getName()));
    sb.append("Options:").append(nl);
    sb.append("-xml delim              : XML input with associated delimiter.").append(nl);
    sb.append("-encoding type          : Input encoding (default: UTF-8).").append(nl);
    sb.append("-printSentenceLengths   : ").append(nl);
    sb.append("-noTokenization         : Split on newline delimiters only.").append(nl);
    sb.append("-suppressEscaping       : Suppress PTB escaping.").append(nl);
    sb.append("-tokenizerOptions opts  : Specify custom tokenizer options.").append(nl);
    sb.append("-tag delim              : Input tokens are tagged. Split tags.").append(nl);
    sb.append("-whitespaceTokenization : Whitespace tokenization only.").append(nl);
    return sb.toString();
  }

  private static Map<String,Integer> argOptionDefs() {
    Map<String,Integer> argOptionDefs = Generics.newHashMap();
    argOptionDefs.put("help", 0);
    argOptionDefs.put("xml", 1);
    argOptionDefs.put("encoding", 1);
    argOptionDefs.put("printSentenceLengths", 0);
    argOptionDefs.put("noTokenization", 0);
    argOptionDefs.put("suppressEscaping", 0);
    argOptionDefs.put("tag", 1);
    argOptionDefs.put("tokenizerOptions", 1);
    argOptionDefs.put("whitespaceTokenization", 0);
    return argOptionDefs;
  }
  
  /**
   * A simple, deterministic sentence-splitter. This method only supports the English
   * tokenizer, so for other languages you should run the tokenizer first and then
   * run this sentence splitter with the "-whitespaceTokenization" option.
   * 
   * @param args Command-line arguments
   */
  public static void main(String[] args) throws IOException {
    final Properties options = StringUtils.argsToProperties(args, argOptionDefs());
    if (options.containsKey("help")) {
      System.err.println(usage());
      System.exit(-1);
    }

    // Command-line flags
    String encoding = options.getProperty("encoding", "utf-8");
    boolean printSentenceLengths = PropertiesUtils.getBool(options,"printSentenceLengths", false);
    String xmlElementDelimiter = options.getProperty("xml", null);
    DocType docType = xmlElementDelimiter == null ? DocType.Plain : DocType.XML;
    String sentenceDelimiter = options.containsKey("-noTokenization") ? System.getProperty("line.separator") : null;
    String tagDelimiter = options.getProperty("tag", null);
    String[] sentenceDelims = null;
    
    // Setup the TokenizerFactory
    int numFactoryFlags = 0;
    boolean suppressEscaping = options.containsKey("suppressEscaping");
    if (suppressEscaping) numFactoryFlags += 1;
    boolean customTokenizer = options.containsKey("tokenizerOptions");
    if (customTokenizer) numFactoryFlags += 1;
    boolean printOriginalText = options.containsKey("printOriginalText");
    if (printOriginalText) numFactoryFlags += 1;
    boolean whitespaceTokenization = options.containsKey("whitespaceTokenization");
    if (whitespaceTokenization) numFactoryFlags += 1;
    if (numFactoryFlags > 1) {
      System.err.println("Only one tokenizer flag allowed at a time: ");
      System.err.println("  -suppressEscaping, -tokenizerOptions, -printOriginalText, -whitespaceTokenization");
      System.exit(-1);
    }
    
    TokenizerFactory<? extends HasWord> tf = null;
    if (suppressEscaping) {
      tf = PTBTokenizer.factory(new CoreLabelTokenFactory(), "ptb3Escaping=false");
    } else if (customTokenizer) {
      tf = PTBTokenizer.factory(new CoreLabelTokenFactory(), options.getProperty("tokenizerOptions"));
    } else if (printOriginalText) {
      tf = PTBTokenizer.factory(new CoreLabelTokenFactory(), "invertible=true");
    } else if (whitespaceTokenization) {
      List<String> whitespaceDelims =
          new ArrayList<String>(Arrays.asList(DocumentPreprocessor.DEFAULT_SENTENCE_DELIMS));
      whitespaceDelims.add(WhitespaceLexer.NEWLINE);
      sentenceDelims = whitespaceDelims.toArray(new String[whitespaceDelims.size()]);
    } else {
      tf = PTBTokenizer.factory(new CoreLabelTokenFactory(), "");
    }
    
    String fileList = options.getProperty("", null);
    String[] files = fileList == null ? new String[1] : fileList.split("\\s+");

    int numSents = 0;
    PrintWriter pw = new PrintWriter(new OutputStreamWriter(System.out, encoding), true);
    for (String file : files) {
      DocumentPreprocessor docPreprocessor;
      if (file == null || file.length() == 0) {
        docPreprocessor = new DocumentPreprocessor(new InputStreamReader(System.in, encoding));
      } else {
        docPreprocessor = new DocumentPreprocessor(file, docType, encoding);
      }
      if (docType == DocType.XML) {
        docPreprocessor.setElementDelimiter(xmlElementDelimiter);
      }
      docPreprocessor.setTokenizerFactory(tf);
      if (sentenceDelimiter != null) {
        docPreprocessor.setSentenceDelimiter(sentenceDelimiter);
      }
      if (tagDelimiter != null) {
        docPreprocessor.setTagDelimiter(tagDelimiter);
      }
      if (sentenceDelims != null) {
        docPreprocessor.setSentenceFinalPuncWords(sentenceDelims);
      }

      for (List<HasWord> sentence : docPreprocessor) {
        numSents++;
        if (printSentenceLengths) {
          System.err.println("Length:\t" + sentence.size());
        }
        boolean printSpace = false;
        for (HasWord word : sentence) {
          if (printOriginalText) {
            CoreLabel cl = (CoreLabel) word;
            if ( ! printSpace) {
              pw.print(cl.get(CoreAnnotations.BeforeAnnotation.class));
              printSpace = true;
            }
            pw.print(cl.get(CoreAnnotations.OriginalTextAnnotation.class));
            pw.print(cl.get(CoreAnnotations.AfterAnnotation.class));
          } else {
            if (printSpace) pw.print(" ");
            printSpace = true;
            pw.print(word.word());
          }
        }
        pw.println();
      }
    }
    pw.close();
    System.err.println("Read in " + numSents + " sentences.");
  }
}
