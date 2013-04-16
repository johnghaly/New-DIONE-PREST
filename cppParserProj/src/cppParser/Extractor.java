package cppParser;

import cppMetrics.LOCMetrics;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Stack;

import cppParser.utils.Log;
import cppParser.utils.MacroExpander;
import cppParser.utils.StringTools;
import cppStructures.CppClass;
import cppStructures.CppDefine;
import cppStructures.CppFile;
import cppStructures.CppFunc;
import cppStructures.CppNamespace;
import cppStructures.CppScope;
import cppStructures.MemberVariable;
import java.util.List;

/**
 * Extractor.java
 * Provides lexical analysis and metrics extraction of C++ source and header files.
 * 
 * @author Harri Pellikka
 */
public class Extractor
{

    

	enum Pass
	{
		PREPASS,
		MAINPASS,
		POSTPASS
	}
	
	enum Mode
	{
		PREPASS_ONLY,
		MAINPASS_ONLY,
		ALL_PASSES
	}
	
	private Pass currentPass = Pass.PREPASS;
	private Mode currentMode = Mode.ALL_PASSES;
	
	// Filename or folder to process
	private String file = "";
    //Directory for the output files;
    private String outputDir="";
	
	// File that is currently being processed
	public static String currentFile = "";
	
	// Current class stack under processing
	private Stack<CppScope> cppScopeStack = new Stack<CppScope>();
	
	// If 'true', all "std"-starting stuff is ignored
	// private boolean ignoreStd = true;
	
	// Current line in the source file (may not reflect the actual processing)
	public static int lineno = 0; 
	public int loc = 0;
	public int lloc = 0;
	public int ploc = 0;
	public int codeLines=0;
	public int emptyLines = 0;
    public int commentedCodeLines = 0;
	public int commentOnlyLines = 0;		//comment lines	
	
	
	// Reference to the singleton parsed object manager
	private ParsedObjectManager objManager;
	
	// The sentence analyzer used to analyze each "raw" sentence
	private SentenceAnalyzer sentenceAnalyzer;
	
	private PreprocessorPass prepassAnalyzer;
    private LOCMetrics locM;
    
	/**
	 * Constructor
	 * @param file Single file or a folder to process
	 */
	public Extractor(String file)
	{
		this.file = file;
		objManager = ParsedObjectManager.getInstance();
	}
    
    /**
	 * Constructor
	 * @param file Single file or a folder to process
     * @param outputDir Directory where metrics are stored
	 */
	public Extractor(String file, String outputDir)
	{
		this.file = file;
        this.outputDir=outputDir;
		objManager = ParsedObjectManager.getInstance();
	}
	
	/**
	 * Starts processing the files
	 */
	public void process()
	{
		Log.d("Processing started.");
		Log.d("Finding files and sorting... ");
		
		long startTime = System.currentTimeMillis();
		sentenceAnalyzer = new SentenceAnalyzer();
		prepassAnalyzer = new PreprocessorPass(this);
		
		FileLoader fileLoader = new FileLoader(this.file);
		
		Log.d("Files sorted in " + (double)(System.currentTimeMillis() - startTime) / 1000.0 + " s.");
		Log.d("Found " + fileLoader.getFiles().size() + " files.");
		
		// Execute the pre-pass if needed
		if(currentMode != Mode.MAINPASS_ONLY)
		{
			currentPass = Pass.PREPASS;
			
			// Parse preprocessor directives
			for(String s : fileLoader.getFiles())
			{
				CppFile cf = new CppFile(s);
				ParsedObjectManager.getInstance().addFile(cf);
				ParsedObjectManager.getInstance().setCurrentFile(cf);
				process(s);
			}
			
			// Expand #include paths
			for(CppFile cf : ParsedObjectManager.getInstance().getFiles())
			{
				cf.expandIncludes();
			}
			
			ParsedObjectManager.getInstance().setCurrentFile("");
			
			// Dump the #include tree for debuggin purposes
			dumpIncludeTree();
			
			// Calculate the pre-pass execution time
			long prepassDuration = System.currentTimeMillis() - startTime;
			Log.d("Found " + PreprocessorPass.defineCount + " #defines.");
			Log.d("Prepass done. (" + (double)(prepassDuration / 1000.0) + " s.)");
		}
		
		// Execute the main pass if needed
		if(currentMode != Mode.PREPASS_ONLY)
		{
			currentPass = Pass.MAINPASS;
			
			// Loop through the found files
			for(String s : fileLoader.getFiles())
			{
				ParsedObjectManager.getInstance().setCurrentFile(s);
                locM=new LOCMetrics();
                ParsedObjectManager.getInstance().addLocMetric(locM);
                sentenceAnalyzer.fileChanged(s, locM);
                
				process(s);
                
			}
			sentenceAnalyzer.lastFileProcessed();
			Log.d("Main pass done.");
		}
		


		// TODO Second pass: fix unknown references / types / ambiguities
        // Verify that no macro calls are in the operands
        // verifyToFile();
		// Dump tree results to a file
		ResultExporter exp=new ResultExporter(outputDir);
        exp.exportAll();
		
		Log.d("Dump done.");
		
		// printTreeResults();
		// printResults();
		
		long duration = System.currentTimeMillis() - startTime;
		
		Log.d("Processing took " + duration / 1000.0 + " s.");
	}
	
	/*
	private void verify()
	{
		ArrayList<CppDefine> defines = new ArrayList<CppDefine>();
		for(CppFile cf : ParsedObjectManager.getInstance().getFiles())
		{
			defines.addAll(cf.getDefines());
		}
		
		for(CppScope cs : ParsedObjectManager.getInstance().getScopes())
		{
			for(CppFunc cf : cs.getFunctions())
			{
				for(String s : cf.getOperands())
				{
					for(CppDefine cd : defines)
					{
						if(s.equals(cd.getName()))
						{
							Log.d("**** FOUND MACRO CALL -> " + s + " FILE: " + cf.fileOfFunc);
						}
					}
				}
			}
		}
	}
	
	private void verifyToFile()
	{
		BufferedWriter writer;
		try {
			writer = new BufferedWriter(new FileWriter("verifydump.txt"));
			
			ArrayList<CppDefine> defines = new ArrayList<CppDefine>();
			for(CppFile cf : ParsedObjectManager.getInstance().getFiles())
			{
				defines.addAll(cf.getDefines());
			}
			
			for(CppScope cs : ParsedObjectManager.getInstance().getScopes())
			{
				for(CppFunc cf : cs.getFunctions())
				{
					for(String s : cf.getOperands())
					{
						for(CppDefine cd : defines)
						{
							if(s.equals(cd.getName()))
							{
								// Log.d("**** FOUND MACRO CALL -> " + s + " FILE: " + cf.fileOfFunc);
								writer.write("File: " + cf.fileOfFunc + " Function: " + cf.getName() + ": Macro call -> " + s + " Macro source: " + cd.getFile() + "\n");
							}
						}
					}
				}
			}
			
			writer.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	*/
	
	/**
	 * Processes the given file.
	 * Processing includes tasks such as constructing internal format lines,
	 * tokenizing them and creating a structure tree of the found data.
	 */
    private String readLine="";
	private void process(String file)
	{
            
		currentFile = file;
		// currentFunc = null;
		// currentScope = null;
		objManager.currentFunc = null;
		ParsedObjectManager.getInstance().currentScope = null;
		// objManager.setDefaultScope();
		
		cppScopeStack.clear();
		// braceCount = 0;
		// funcBraceCount = 0;
		// this.lineDone = false;
		lineno = 0;
		// Initialize macro expander
		
		try
		{
			lineno = 1;
			BufferedReader reader = new BufferedReader(new FileReader(file));
			String line = "";
			String commentLine = "";
			boolean commentFound=false; //Currently read line contains comments
            boolean codeFound=false;  //Currently read line contains code
			boolean skipComment = false;
			boolean isMultiLineComment = false;
			char c;
			boolean stringOpen = false;
			boolean charOpen = false;
			
			long extractStart = System.currentTimeMillis();
			
			while((c = (char)reader.read()) != (char)-1)
			{
                
				if(c == '\n')
				{
					loc++;
					lineno++;
					ploc++;
                    if(!line.trim().isEmpty())
                        codeFound=true;
                    
					addLine(codeFound,commentFound);
					
					// Handle preprocessor directives
					if(line.startsWith("#"))
					{
						line = line.trim();
						if(!stringOpen && !charOpen && line.charAt(line.length() - 1) != '\\')
						{
							lloc++;
							switch(currentPass)
							{
							case PREPASS:
								prepassAnalyzer.process(line);
								break;
							}
							
							line = "";
							commentLine = "";	
						}
						else
						{
							lloc++;
							line = line.substring(0, line.length() - 1);
							continue;
						}
					}
                    readLine="";
					commentFound=false;
                    codeFound=false;
				}else{
                    if(c!='\r')
                        readLine+=c;
                }
				
				// Skips characters until the current comment ends
				if(skipComment)
				{
					if(!isMultiLineComment)
					{
						if(c == '\r' || c == '\n')
						{
							
								
							skipComment = false;
							objManager.oneLineComments.add(commentLine);
							commentLine = "";
						}
					}
					else if(isMultiLineComment)
					{
						if(commentLine.endsWith("*/"))
						{
							skipComment = false;
							isMultiLineComment = false;
							objManager.multiLineComments.add(commentLine);
							commentLine = "";
						}
					}
					
					if(skipComment)
					{ 
						if(c!=' ' && c!='\n' && c!='\t' && c!='\r')
							commentFound=true;
						commentLine += c;
					}					
					continue;
				}
				
				// Check if a comment line is about to start
				if(!stringOpen && (c == '/' || (c == '*' && commentLine.startsWith("/"))))
				{
					commentLine += c;
					if(commentLine.length() > 1)
					{
						if(commentLine.startsWith("//"))
						{
							if(StringTools.getQuoteCount(line) % 2 == 0)
							{
								// Skip until new line
								isMultiLineComment = false;
								skipComment = true;
							}
							else
							{
								commentLine = "";
							}
						}
							
						if(commentLine.startsWith("/*"))
						{
							if(StringTools.getQuoteCount(line) % 2 == 0)
							{
								isMultiLineComment = true;
								skipComment = true;
							}
							else
							{
								commentLine = "";
							}
						}
					}					
					continue;
				}
				else
				{
                    if(commentLine.length() == 1)
                    {
                        commentLine="";
                        line += "/";
                    }
                }
				
				// Add a character to the "line"
				if(c != '\r' && c != '\n' && c != '\t')
				{
					if(c == '"' && ((line.length() > 0 ? line.charAt(line.length() - 1) != '\\' : true) || (line.length() > 1 ? line.charAt(line.length() - 2) == '\\' : true)) && !charOpen)
					{
						line += "\"";
						stringOpen = !stringOpen;
					}
					else if(c == '\'' && ((line.length() > 0 ? line.charAt(line.length() - 1) != '\\' : true) || (line.length() > 1 ? line.charAt(line.length() - 2) == '\\' : true)) && !stringOpen)
					{
						line += "\'";
						charOpen = !charOpen;
					}
					else
					{
						if(line.length() == 0 && c == ' ')
						{
							
						}
						else
						{
							line += c;
						}
					}				
					
				}
				else if(line.length() > 0 && line.charAt(line.length() - 1) != ' ')
				{
					// Add a space (just one, even if there's multiple)
					line += ' ';					
				}
				
				// If the line ends, start lexing it
				if(!stringOpen && !charOpen && (c == ';' || c == '{' || c == '}' || (isVisibilityStatement(c, line))))
				{
					lloc++;
					// lexLine(line);
					line.trim();
					
					switch(currentPass)
					{
					case PREPASS:
						prepassAnalyzer.process(line);
						break;
					case MAINPASS:
						long extractDuration = System.currentTimeMillis() - extractStart;
						if(extractDuration > 10)
						{
							Log.d(extractDuration + " Extraction: " + currentFile + ":" + lineno);
						}
						sentenceAnalyzer.lexLine(line);
						break;
					}
					
					// sentenceAnalyzer.lexLineHM(line);
					line = "";
					commentLine = "";
                    codeFound=true;
                    extractStart = System.currentTimeMillis();
				}
			}
			addLine(codeFound,commentFound);
            resetLOCCounter();
			loc++;
			
			// Finally, close the reader
			reader.close();
			
		} 
		catch (FileNotFoundException e)
		{
			e.printStackTrace();
		} 
		catch (IOException e)
		{
			e.printStackTrace();
		}
	}
	/**
	 * This method counts line for physical loc metrics
	 * @param codeFound should be true if current line contains code
	 * @param commentFound should be true if current line contains comments
	 */
	private void addLine(boolean codeFound, boolean commentFound){
        if(currentPass==Pass.MAINPASS){
		if(codeFound){
            if(commentFound)
				commentedCodeLines++;
			else
				codeLines++;
		}else{
			if(commentFound)
				commentOnlyLines++;
			else
				emptyLines++;
		}
        }
        
	}
	
    private void resetLOCCounter() {
        if(currentPass==Pass.MAINPASS){
        locM.codeOnlyLines=codeLines;
        locM.emptyLines=emptyLines;
        locM.commentLines=commentOnlyLines;
        locM.commentedCodeLines=commentedCodeLines;
        
        codeLines=0;
        emptyLines=0;
        commentOnlyLines=0;
        commentedCodeLines=0;
        }
    }
	
	/**
	 * Checks whether or not the line forms a 'visibility statement' found usually in headers
	 * @param c The latest char to add
	 * @param line The line formed so far
	 * @return 'true' if the line forms either "public", "protected" or "private" statement, 'false' otherwise
	 */
	private boolean isVisibilityStatement(char c, String line)
	{
		if(c != ':') return false;
		else
		{
			if(line.endsWith("public:") || line.endsWith("protected:") || line.endsWith("private:")) return true;
		}
		return false;
	}
	
	private void dumpIncludeTree()
	{
		BufferedWriter writer;
		try
		{
			writer = new BufferedWriter(new FileWriter("includetree.txt"));
			
			/*
			for(CppFile cf : ParsedObjectManager.getInstance().getFiles())
			{
				writer.write(cf.getFilename() + "\n");
				for(String s : cf.getIncludes())
				{
					writer.write("  " + s + "\n");
				}
			}
			*/
			
			Log.d("Dumping include tree...");
			for(CppFile cf : ParsedObjectManager.getInstance().getFiles())
			{
				cf.dump(writer, new HashSet<CppFile>(), 0);
				writer.write("\n");
			}
			
			
			writer.close();
		}
		catch(Exception e)
		{
			
		}
	}
	
	/**
	 * Dumps the results into a text file
	 */
	
	
	/**
	 * Prints the results
	 */
	/*
	private void printTreeResults()
	{
		Log.d("Tree results");
		for(CppScope cs : objManager.getScopes())
		{
			Log.d(" - " + cs.getName());
			for(CppFunc cp : cs.getFunctions())
			{
				Log.d("    - " + cp.getType() + " | " + cp.getName());
			}
		}
	}
	*/
	
	/**
	 * Prints information out to console
	 */
	/*
	private void printResults()
	{
		// Print #defines
		if(objManager.defines.size() > 0)
		{
			Log.d("defines");
			for(String s : objManager.defines) Log.d(" - " + s);
			Log.d();
		}
		
		// Print #includes
		if(objManager.includes.size() > 0)
		{
			Log.d("includes");
			for(String s : objManager.includes) Log.d(" - " + s);
			Log.d();
		}
		
		// Print class names
		if(objManager.classes.size() > 0)
		{
			Log.d("classes");
			for(String s : objManager.classes)	Log.d(" - " + s);
			Log.d();
		}
		
		// Print single-line comments
		if(objManager.oneLineComments.size() > 0)
		{
			Log.d("oneline comments");
			for(String s : objManager.oneLineComments)	Log.d(" - " + s);
			Log.d();
		}
		
		// Print multi-line comments
		if(objManager.multiLineComments.size() > 0)
		{
			Log.d("multiline comments");
			for(String s : objManager.multiLineComments) Log.d(" - " + s);
			Log.d();
		}
		
		// Print halstead counting
		if (ParsedObjectManager.getInstance().currentFunc.getOperatorCount() > 0){
			Log.d("Operators");
			Log.d("" + ParsedObjectManager.getInstance().currentFunc.getLength());
		}
	}
	*/
}
