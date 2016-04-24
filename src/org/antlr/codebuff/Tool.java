package org.antlr.codebuff;

import org.antlr.codebuff.gui.GUIController;
import org.antlr.codebuff.misc.CodeBuffTokenStream;
import org.antlr.codebuff.misc.LangDescriptor;
import org.antlr.v4.runtime.ANTLRFileStream;
import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.misc.Pair;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.antlr.codebuff.misc.BuffUtils.filter;

/** Grammar must have WS/comments on hidden channel
 *
 * Testing:
 *
 * Tool  -dbg  -antlr     corpus/antlr4/training      grammars/org/antlr/codebuff/tsql.g4
 * Tool  -dbg  -sqlite    corpus/sqlite/training      corpus/sqlite/testing/t1.sql
 * Tool  -dbg  -tsql      corpus/tsql/training        corpus/tsql/testing/select1.sql
 * Tool  -dbg  -plsql     corpus/plsql/training       corpus/plsql/testing/condition15.sql
 * Tool  -dbg  -java      corpus/java/training/stringtemplate4     src/org/antlr/codebuff/Tool.java
 * Tool  -dbg  -java      corpus/java/training/antlr4-tool   corpus/java/training/stringtemplate4/org/stringtemplate/v4/AutoIndentWriter.java
 */
public class Tool {
	public static boolean showFileNames = false;
	public static boolean showTokens = false;

	public static final LangDescriptor JAVA_DESCR =
		new LangDescriptor("java", ".*\\.java", JavaLexer.class, JavaParser.class, "compilationUnit", 4);
	public static final LangDescriptor ANTLR4_DESCR =
		new LangDescriptor("antlr", ".*\\.g4", ANTLRv4Lexer.class, ANTLRv4Parser.class, "grammarSpec", 4);
	public static final LangDescriptor SQLITE_DESCR =
		new LangDescriptor("sqlite", ".*\\.sql", SQLiteLexer.class, SQLiteParser.class, "parse", 4);
	public static final LangDescriptor TSQL_DESCR =
		new LangDescriptor("tsql", ".*\\.sql", tsqlLexer.class, tsqlParser.class, "tsql_file", 4);

	public static LangDescriptor[] languages = new LangDescriptor[]{
		JAVA_DESCR,
		ANTLR4_DESCR,
		SQLITE_DESCR,
		TSQL_DESCR,
		new LangDescriptor("plsql", ".*\\.sql", plsqlLexer.class, plsqlParser.class, "compilation_unit", 4)
	};

	public static void main(String[] args)
		throws Exception {
		if ( args.length<2 ) {
			System.err.println("ExtractFeatures [-dbg] [-java|-antlr|-sqlite|-tsql|-plsql] root-dir-of-samples test-file");
		}
		int arg = 0;
		boolean collectAnalysis = false;
		if ( args[arg].equals("-dbg") ) {
			collectAnalysis = true;
			arg++;
		}
		String language = args[arg++];
		language = language.substring(1);
		String corpusDir = args[arg++];
		String testFilename = args[arg];
		String output = "???";
		InputDocument testDoc;
		GUIController controller;
		List<TokenPositionAnalysis> analysisPerToken;
		Pair<String, List<TokenPositionAnalysis>> results;
		LangDescriptor lang = null;
		long start, stop;
		for (int i = 0; i<languages.length; i++) {
			if ( languages[i].name.equals(language) ) {
				lang = languages[i];
				break;
			}
		}
		if ( lang!=null ) {
			Corpus corpus = new Corpus(corpusDir, Tool.JAVA_DESCR);
			corpus.train();
			testDoc = load(testFilename, lang);
			start = System.nanoTime();
			Formatter formatter = new Formatter(corpus);
			output = formatter.format(testDoc, collectAnalysis);
			stop = System.nanoTime();
			analysisPerToken = formatter.getAnalysisPerToken();

			dumpAccuracy(testDoc, analysisPerToken);

			List<Token> wsTokens = filter(formatter.originalTokens.getTokens(),
			                              t -> t.getChannel()!=Token.DEFAULT_CHANNEL);
			String originalWS = tokenText(wsTokens);

			CommonTokenStream formatted_tokens = tokenize(output, corpus.language.lexerClass);
			wsTokens = filter(formatted_tokens.getTokens(),
			                  t -> t.getChannel()!=Token.DEFAULT_CHANNEL);
			String formattedWS = tokenText(wsTokens);

			float editDistance = levenshteinDistance(originalWS, formattedWS);
			System.out.println("Levenshtein distance of ws: "+editDistance);
			editDistance = levenshteinDistance(testDoc.content, output);
			System.out.println("Levenshtein distance: "+editDistance);
			System.out.println("ws len orig="+originalWS.length()+", "+formattedWS.length());

			controller = new GUIController(analysisPerToken, testDoc, output, lang.lexerClass);
			controller.show();
//			System.out.println(output);
			System.out.printf("formatting time %ds\n", (stop-start)/1_000_000);
			System.out.printf("classify calls %d, hits %d rate %f\n",
			                  kNNClassifier.nClassifyCalls, kNNClassifier.nClassifyCacheHits,
			                  kNNClassifier.nClassifyCacheHits/(float) kNNClassifier.nClassifyCalls);
			System.out.printf("kNN calls %d, hits %d rate %f\n",
			                  kNNClassifier.nNNCalls, kNNClassifier.nNNCacheHits,
			                  kNNClassifier.nNNCacheHits/(float) kNNClassifier.nNNCalls);
		}
	}

	public static void dumpAccuracy(InputDocument testDoc, List<TokenPositionAnalysis> analysisPerToken) {
		System.out.println("num real tokens from 1: "+getNumberRealTokens(testDoc.tokens, 1, testDoc.tokens.size()-2)); // don't include first token nor EOF
		int n = 0; // should be number of real tokens - 1 (we don't process 1st token)
		int n_align_compares = 0;
		int correct_ws = 0;
		int n_none = 0;
		int n_nl = 0;
		int n_sp = 0;
		int correct_none = 0;
		int correct_nl = 0;
		int correct_sp = 0;
		int correct_align = 0;
		/*
		 predicted  |   actual  |   match
		 ---------  -   ------  -   ------
		            |           |     x
		            |   ' '     |
		            |   '\n'    |
		    '\n'    |           |
		    '\n'    |   ' '     |
		    '\n'    |   '\n'    |     x
		    ' '     |           |
		    ' '     |   ' '     |     x
		    ' '     |   '\n'    |
		 */
		for (TokenPositionAnalysis a : analysisPerToken) {
			if ( a==null ) continue;
			n++;
			if ( a.actualWS==0 ) {
				n_none++;
			}
			else if ( (a.actualWS&0xFF)==Trainer.CAT_INJECT_NL ) {
				n_nl++;
			}
			else if ( (a.actualWS&0xFF)==Trainer.CAT_INJECT_WS ) {
				n_sp++;
			}

			if ( a.wsPrediction==0 && a.wsPrediction==a.actualWS ) {
				correct_none++;
			}
			else if ( (a.wsPrediction&0xFF)==Trainer.CAT_INJECT_NL && a.wsPrediction==a.actualWS ) {
				correct_nl++;
			}
			else if ( (a.wsPrediction&0xFF)==Trainer.CAT_INJECT_WS && a.wsPrediction==a.actualWS ) {
				correct_sp++;
			}
			if ( a.wsPrediction==a.actualWS ) {
				correct_ws++;
			}

			if ( (a.wsPrediction&0xFF)==Trainer.CAT_INJECT_NL ) {
				n_align_compares++;
				// if we predicted newline *and* actual was newline, check alignment misclassification
				// Can't compare if both aren't supposed to align. If we predict '\n' but actual is ' ',
				// alignment will always fail to match. Similarly, if we predict no-'\n' but actual is '\n',
				// we didn't compute align so can't compare.
				if ( a.alignPrediction==a.actualAlign ) {
					correct_align++;
				}
			}
		}
		float none_accuracy = correct_none/(float) n_none;
		System.out.printf("correct none / num none = %d/%d, %4.3f%%\n",
		                  correct_none, n_none, none_accuracy*100);
		float nl_accuracy = correct_nl/(float) n_nl;
		System.out.printf("correct nl / num nl = %d/%d, %4.3f%%\n",
		                  correct_nl, n_nl, nl_accuracy*100);
		float sp_accuracy = correct_sp/(float) n_sp;
		System.out.printf("correct sp / num ws = %d/%d, %4.3f%%\n",
		                  correct_sp, n_sp, sp_accuracy*100);

		double overall_ws_accuracy = correct_ws/(float) n;
		System.out.printf("overall ws correct = %d/%d %4.3f%%\n",
		                  correct_ws, n, overall_ws_accuracy*100);

		double align_accuracy = correct_align/(float) n_align_compares;
		System.out.printf("align correct = %d/%d %4.3f%%\n",
		                  correct_align, n_align_compares, align_accuracy*100.0);
	}

	public static CommonTokenStream tokenize(String doc, Class<? extends Lexer> lexerClass)
		throws Exception {
		ANTLRInputStream input = new ANTLRInputStream(doc);
		Lexer lexer = getLexer(lexerClass, input);

		CommonTokenStream tokens = new CodeBuffTokenStream(lexer);
		tokens.fill();
		return tokens;
	}

	/**
	 * Parse doc and fill tree and tokens fields
	 */
	public static void parse(InputDocument doc, LangDescriptor language)
		throws Exception {
		ANTLRInputStream input = new ANTLRInputStream(doc.content);
		Lexer lexer = getLexer(language.lexerClass, input);
		input.name = doc.fileName;

		doc.tokens = new CodeBuffTokenStream(lexer);

		if ( showTokens ) {
			doc.tokens.fill();
			for (Object tok : doc.tokens.getTokens()) {
				System.out.println(tok);
			}
		}

		doc.parser = getParser(language.parserClass, doc.tokens);
		doc.parser.setBuildParseTree(true);
		Method startRule = language.parserClass.getMethod(language.startRuleName);
		doc.tree = (ParserRuleContext) startRule.invoke(doc.parser, (Object[]) null);
	}

	public static Parser getParser(Class<? extends Parser> parserClass, CommonTokenStream tokens) throws NoSuchMethodException, InstantiationException, IllegalAccessException, java.lang.reflect.InvocationTargetException {
		Constructor<? extends Parser> parserCtor =
			parserClass.getConstructor(TokenStream.class);
		return parserCtor.newInstance(tokens);
	}

	public static Lexer getLexer(Class<? extends Lexer> lexerClass, ANTLRInputStream input) throws NoSuchMethodException, InstantiationException, IllegalAccessException, java.lang.reflect.InvocationTargetException {
		Constructor<? extends Lexer> lexerCtor =
			lexerClass.getConstructor(CharStream.class);
		return lexerCtor.newInstance(input);
	}

	public static List<InputDocument> load(List<String> fileNames, LangDescriptor language)
		throws Exception
	{
		List<InputDocument> documents = load(fileNames, language.tabSize);
		for (InputDocument doc : documents) {
			parse(doc, language);
		}
		return documents;
	}

	/** Get all file contents into input doc list */
	public static List<InputDocument> load(List<String> fileNames, int tabSize)
		throws Exception
	{
		List<InputDocument> input = new ArrayList<>(fileNames.size());
		int i = 0;
		for (String f : fileNames) {
			InputDocument doc = load(f, tabSize);
			doc.index = i++;
			input.add(doc);
		}
		System.out.println(input.size()+" files");
		return input;
	}

	public static InputDocument load(String fileName, LangDescriptor language)
		throws Exception
	{
		InputDocument document = load(fileName, language.tabSize);
		parse(document, language);
		return document;
	}

	public static InputDocument load(String fileName, int tabSize)
		throws Exception
	{
		Path path = FileSystems.getDefault().getPath(fileName);
		byte[] filearray = Files.readAllBytes(path);
		String content = new String(filearray);
		String notabs = expandTabs(content, tabSize);
		return new InputDocument(fileName, notabs);
	}


	public static List<String> getFilenames(File f, String inputFilePattern) throws Exception {
		List<String> files = new ArrayList<>();
		getFilenames_(f, inputFilePattern, files);
		return files;
	}

	public static void getFilenames_(File f, String inputFilePattern, List<String> files) {
		// If this is a directory, walk each file/dir in that directory
		if (f.isDirectory()) {
			String flist[] = f.list();
			for (String aFlist : flist) {
				getFilenames_(new File(f, aFlist), inputFilePattern, files);
			}
		}

		// otherwise, if this is an input file, load it!
		else if ( inputFilePattern==null || f.getName().matches(inputFilePattern) ) {
		  	files.add(f.getAbsolutePath());
		}
	}

	public static String join(int[] array, String separator) {
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < array.length; i++) {
			builder.append(array[i]);
			if (i < array.length - 1) {
				builder.append(separator);
			}
		}

		return builder.toString();
	}

	public static String join(String[] array, String separator) {
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < array.length; i++) {
			builder.append(array[i]);
			if (i < array.length - 1) {
				builder.append(separator);
			}
		}

		return builder.toString();
	}

	public static List<CommonToken> copy(CommonTokenStream tokens) {
		List<CommonToken> copy = new ArrayList<>();
		tokens.fill();
		for (Token t : tokens.getTokens()) {
			copy.add(new CommonToken(t));
		}
		return copy;
	}

	public static int L0_Distance(boolean[] categorical, int[] A, int[] B) {
		int count = 0; // count how many mismatched categories there are
		for (int i=0; i<A.length; i++) {
			if ( categorical[i] ) {
				if ( A[i] != B[i] ) {
					count++;
				}
			}
		}
		return count;
	}

	/** A distance of 0 should count much more than non-0. Also, penalize
	 *  mismatches closer to current token than those farther away.
	 */
	public static double weightedL0_Distance(FeatureMetaData[] featureTypes, int[] A, int[] B) {
		double count = 0; // count how many mismatched categories there are
		for (int i=0; i<A.length; i++) {
			FeatureType type = featureTypes[i].type;
			if ( type==FeatureType.TOKEN ||
				 type==FeatureType.RULE ||
				 type==FeatureType.INT ||
				 type==FeatureType.BOOL)
			{
				if ( A[i] != B[i] ) {
					count += featureTypes[i].mismatchCost;
				}
			}
			else if ( type==FeatureType.COLWIDTH ) {
				// threshold any len > RIGHT_MARGIN_ALARM
				int a = A[i];
				int b = B[i];
//				int a = Math.min(A[i], WIDE_LIST_THRESHOLD);
//				int b = Math.min(B[i], WIDE_LIST_THRESHOLD);
//				count += Math.abs(a-b) / (float) WIDE_LIST_THRESHOLD; // normalize to 0..1
//				count += sigmoid(a-b, 37);
				double delta = Math.abs(sigmoid(a, 43)-sigmoid(b, 43));
				count += delta;
			}
		}
		return count;
	}

	public static double sigmoid(int x, float center) {
		return 1.0 / (1.0 + Math.exp(-0.9*(x-center)));
	}

	public static int max(List<Integer> Y) {
		int max = 0;
		for (int y : Y) max = Math.max(max, y);
		return max;
	}

	public static int sum(int[] a) {
		int s = 0;
		for (int x : a) s += x;
		return s;
	}

	/** from https://en.wikipedia.org/wiki/Levenshtein_distance
	 *  "It is always at least the difference of the sizes of the two strings."
	 *  "It is at most the length of the longer string."
	 */
	public static float levenshteinDistance(String s, String t) {
	    // degenerate cases
	    if (s.equals(t)) return 0;
	    if (s.length() == 0) return t.length();
	    if (t.length() == 0) return s.length();

	    // create two work vectors of integer distances
	    int[] v0 = new int[t.length() + 1];
	    int[] v1 = new int[t.length() + 1];

	    // initialize v0 (the previous row of distances)
	    // this row is A[0][i]: edit distance for an empty s
	    // the distance is just the number of characters to delete from t
	    for (int i = 0; i < v0.length; i++) {
			v0[i] = i;
		}

	    for (int i = 0; i < s.length(); i++) {
	        // calculate v1 (current row distances) from the previous row v0

	        // first element of v1 is A[i+1][0]
	        //   edit distance is delete (i+1) chars from s to match empty t
	        v1[0] = i + 1;

	        // use formula to fill in the rest of the row
	        for (int j = 0; j < t.length(); j++)
	        {
	            int cost = s.charAt(i) == t.charAt(j) ? 0 : 1;
	            v1[j + 1] = Math.min(
								Math.min(v1[j] + 1, v0[j + 1] + 1),
								v0[j] + cost);
	        }

	        // copy v1 (current row) to v0 (previous row) for next iteration
			System.arraycopy(v1, 0, v0, 0, v0.length);
	    }

	    int d = v1[t.length()];
//		int min = Math.abs(s.length()-t.length());
		int max = Math.max(s.length(), t.length());
		return d / (float)max;
	}

	/* Compare whitespace and give an approximate Levenshtein distance /
	   edit distance. MUCH faster to use this than pure Levenshtein which
	   must consider all of the "real" text that is in common.

		when only 1 kind of char, just substract lengths
		Orig    Altered Distance
		AB      A B     1
		AB      A  B    2
		AB      A   B   3
		A B     A  B    1

		A B     AB      1
		A  B    AB      2
		A   B   AB      3

		when ' ' and '\n', we count separately.

		A\nB    A B     spaces delta=1, newline delete=1, distance = 2
		A\nB    A  B    spaces delta=2, newline delete=1, distance = 3
		A\n\nB  A B     spaces delta=1, newline delete=2, distance = 3
		A\n \nB A B     spaces delta=0, newline delete=2, distance = 2
		A\n \nB A\nB    spaces delta=1, newline delete=1, distance = 2
		A \nB   A\n B   spaces delta=0, newline delete=0, distance = 0
						levenshtein would count this as 2 I think but
						for our doc distance, I think it's ok to measure as same
	 */
//	public static int editDistance(String s, String t) {
//	}

	/*
			A \nB   A\n B   spaces delta=0, newline delete=0, distance = 0
						levenshtein would count this as 2 I think but
						for our doc distance, I think it's ok to measure as same
	 */
	public static int whitespaceEditDistance(String s, String t) {
		int s_spaces = count(s, ' ');
		int s_nls = count(s, '\n');
		int t_spaces = count(t, ' ');
		int t_nls = count(t, '\n');
		return Math.abs(s_spaces - t_spaces) + Math.abs(s_nls - t_nls);
	}

	/** Compute a document difference metric 0-1.0 between two documents that
	 *  are identical other than (likely) the whitespace and comments.
	 *
	 *  1.0 means the docs are maximally different and 0 means docs are identical.
	 *
	 *  The Levenshtein distance between the docs counts only
	 *  whitespace diffs as the non-WS content is identical.
	 *  Levenshtein distance is bounded by 0..max(len(doc1),len(doc2)) so
	 *  we normalize the distance by dividing by max WS count.
	 *
	 *  TODO: can we simplify this to a simple walk with two
	 *  cursors through the original vs formatted counting
	 *  mismatched whitespace? real text are like anchors.
	 */
	public static double docDiff(String original,
	                             String formatted,
	                             Class<? extends Lexer> lexerClass)
		throws Exception
	{
		// Grammar must strip all but real tokens and whitespace (and put that on hidden channel)
		CommonTokenStream original_tokens = tokenize(original, lexerClass);
//		String s = original_tokens.getText();
		CommonTokenStream formatted_tokens = tokenize(formatted, lexerClass);
//		String t = formatted_tokens.getText();

		// walk token streams and examine whitespace in between tokens
		int i = 1;
		int ws_distance = 0;
		int original_ws = 0;
		int formatted_ws = 0;
		while ( true ) {
			Token ot = original_tokens.LT(i);
			if ( ot==null || ot.getType()==Token.EOF ) break;
			List<Token> ows = original_tokens.getHiddenTokensToLeft(ot.getTokenIndex());
			original_ws += tokenText(ows).length();

			Token ft = formatted_tokens.LT(i);
			if ( ft==null || ft.getType()==Token.EOF ) break;
			List<Token> fws = formatted_tokens.getHiddenTokensToLeft(ft.getTokenIndex());
			formatted_ws += tokenText(fws).length();

			ws_distance += whitespaceEditDistance(tokenText(ows), tokenText(fws));
			i++;
		}
		// it's probably ok to ignore ws diffs after last real token

		int max_ws = Math.max(original_ws, formatted_ws);
		double normalized_ws_distance = ((float) ws_distance)/max_ws;
		return normalized_ws_distance;
	}

	/** Compare an input document's original text with its formatted output
	 *  and return the ratio of the incorrectWhiteSpaceCount to total whitespace
	 *  count in the original document text. It is a measure of document
	 *  similarity.
	 */
//	public static double compare(InputDocument doc,
//	                             String formatted,
//	                             Class<? extends Lexer> lexerClass)
//		throws Exception {
//	}

	public static String tokenText(List<Token> tokens) {
		if ( tokens==null ) return "";
		StringBuilder buf = new StringBuilder();
		for (Token t : tokens) {
			buf.append(t.getText());
		}
		return buf.toString();
	}

	public static int getNumberRealTokens(CommonTokenStream tokens, int from, int to) {
		if ( tokens==null ) return 0;
		int n = 0;
		if ( from<0 ) from = 0;
		if ( to>tokens.size() ) to = tokens.size()-1;
		for (int i = from; i <= to; i++) {
			Token t = tokens.get(i);
			if ( t.getChannel()==Token.DEFAULT_CHANNEL ) {
				n++;
			}
		}
		return n;
	}

	public static String spaces(int n) {
		return sequence(n, " ");
//		StringBuilder buf = new StringBuilder();
//		for (int sp=1; sp<=n; sp++) buf.append(" ");
//		return buf.toString();
	}

	public static String newlines(int n) {
		return sequence(n, "\n");
//		StringBuilder buf = new StringBuilder();
//		for (int sp=1; sp<=n; sp++) buf.append("\n");
//		return buf.toString();
	}

	public static String sequence(int n, String s) {
		StringBuilder buf = new StringBuilder();
		for (int sp=1; sp<=n; sp++) buf.append(s);
		return buf.toString();
	}

	public static int count(String s, char x) {
		int n = 0;
		for (int i = 0; i<s.length(); i++) {
			if ( s.charAt(i)==x ) {
				n++;
			}
		}
		return n;
	}

	public static String expandTabs(String s, int tabSize) {
		if ( s==null ) return null;
		StringBuilder buf = new StringBuilder();
		int col = 0;
		for (int i = 0; i<s.length(); i++) {
			char c = s.charAt(i);
			switch ( c ) {
				case '\n' :
					col = 0;
					buf.append(c);
					break;
				case '\t' :
					int n = tabSize-col%tabSize;
					col+=n;
					buf.append(spaces(n));
					break;
				default :
					col++;
					buf.append(c);
					break;
			}
		}
		return buf.toString();
	}

	public static String dumpWhiteSpace(String s) {
		String[] whiteSpaces = new String[s.length()];
		for (int i=0; i<s.length(); i++) {
			char c = s.charAt(i);
			switch ( c ) {
				case '\n' :
					whiteSpaces[i] = "\\n";
					break;
				case '\t' :
					whiteSpaces[i] = "\\t";
					break;
				case '\r' :
					whiteSpaces[i] = "\\r";
					break;
				case '\u000C' :
					whiteSpaces[i] = "\\u000C";
					break;
				case ' ' :
					whiteSpaces[i] = "ws";
					break;
				default :
					whiteSpaces[i] = String.valueOf(c);
					break;
			}
		}
		return join(whiteSpaces, " | ");
	}

	// In some case, before a new line sign, there maybe some white space.
	// But those white spaces won't change the look of file.
	// To compare if two WS are the same, we should remove all the shite space before the first '\n'
	public static boolean TwoWSEqual(String a, String b) {
		String newA = a;
		String newB = b;

		int aStartNLIndex = a.indexOf('\n');
		int bStartNLIndex = b.indexOf('\n');

		if (aStartNLIndex > 0) newA = a.substring(aStartNLIndex);
		if (bStartNLIndex > 0) newB = b.substring(bStartNLIndex);

		return newA.equals(newB);
	}

	public static void printOriginalFilePiece(InputDocument doc, CommonToken originalCurToken) {
		System.out.println(doc.getLine(originalCurToken.getLine()-1));
		System.out.println(doc.getLine(originalCurToken.getLine()));
		System.out.print(Tool.spaces(originalCurToken.getCharPositionInLine()));
		System.out.println("^");
	}

	public static class Foo {
		public static void main(String[] args) throws Exception {
			ANTLRv4Lexer lexer = new ANTLRv4Lexer(new ANTLRFileStream("grammars/org/antlr/codebuff/ANTLRv4Lexer.g4"));
			CommonTokenStream tokens = new CodeBuffTokenStream(lexer);
			ANTLRv4Parser parser = new ANTLRv4Parser(tokens);
			ANTLRv4Parser.GrammarSpecContext tree = parser.grammarSpec();
			System.out.println(tree.toStringTree(parser));
		}
	}
}