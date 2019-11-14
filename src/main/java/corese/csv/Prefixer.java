package corese.csv ;

import corese.Main ;
import corese.Writer ;
import java.util.Set ;
import java.util.Map ;
import java.util.List ;
import java.util.Arrays ;
import java.util.Objects ;
import java.util.HashMap ;
import java.io.IOException ;
import java.util.ArrayList ;
import java.nio.file.Files ;
import java.nio.file.Paths ;
import java.util.logging.Level ;
import java.util.stream.Stream ;
import java.util.regex.Pattern ;
import java.util.logging.Logger ;
import java.util.stream.IntStream ;
import java.util.stream.Collectors ;
import java.util.function.Consumer ;
import fr.inria.edelweiss.kgtool.load.Load ;
import fr.inria.acacia.corese.api.IDatatype ;
import fr.inria.edelweiss.kgraph.core.Graph ;
import fr.inria.edelweiss.kgram.core.Mapping ;
import fr.inria.edelweiss.kgram.core.Mappings ;
import static java.util.stream.Collectors.toSet;
import fr.inria.edelweiss.kgraph.rule.RuleEngine ;
import fr.inria.edelweiss.kgraph.query.QueryProcess ;
import fr.inria.acacia.corese.exceptions.EngineException ;

/**
 *
 * @author ryahiaoui
 */
public class Prefixer {
        
    private static final String 

    SPARQL_SEARCH_IN_URI =    "  PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>  \n " +
                              "  SELECT DISTINCT ?URI ?SUFFIX WHERE {                  \n " +
                              "   ?URI ?P ?O .                                         \n " +
                              "   BIND ( STRAFTER ( STR(?URI) , \"#\" ) AS ?SUFFIX ) . \n " +
                              "   FILTER REGEX( ?SUFFIX , \"^^{0}$\", \"i\" )        . \n " +
                              " } "  ,
    
    SPARQL_SEARCH_IN_LABEL =  "  PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>  \n " +
                              "  SELECT DISTINCT ?URI ?CLAZZ WHERE {                   \n " +
                              "    ?URI rdfs:label ?LABEL                              \n " +
                              "    FILTER REGEX( STR(?LABEL), \"^^{0}$\", \"i\")       \n " +
                              "    BIND ( STRAFTER ( STR(?URI) , \"#\" ) AS ?CLAZZ )   \n " +
                              " } "  ;
    
    private static final String EMPTY_RESULT   = "::EMPTY::" ;
    
    private static final String NULL_RESULT    = "::NULL::"  ;
    
    private static volatile Prefixer _instance =  null       ;
     
    private static Graph        g   ;
    private static Load         ld  ;
    private static RuleEngine   re  ;

    private static String getContainedSeparator(String label, List<String> intra_seps) {
        return intra_seps.stream()
                         .filter( string -> label.contains(string))
                         .findFirst().orElse("");

    }
   
    enum FORMAT { TTL, XML , CSV }  ;
 
    static int loop  = 0            ;
 
    private Prefixer() {}

    public static Prefixer getInstance( String fileToLoad, boolean entailment ) {
             if (_instance == null) {
                    synchronized (Main.class) {
                            if (_instance == null) {
                                    _instance = new Prefixer() ;
                                    initialize (fileToLoad , entailment) ;
                            }
                    }
            }
            return _instance ;
    }
      
    private static void initialize( String fileToLoad, boolean entailment ) {
          
        try {
              g  = Graph.create(entailment) ;             
              ld = Load.create(g)           ;
              loadFile( fileToLoad  )       ;
         }
         catch (Exception ex) {
               ex.printStackTrace() ;
               Throwable[] suppressed = ex.getSuppressed() ;
               for( Throwable cause : suppressed )  {
                    System.err.println(cause.getMessage()) ;
               }
               System.out.println(" ") ;
         }
    }
 
    private static void loadFile ( String fileToLoad )       {
        System.out.println("                              ") ;
        System.out.println(" Loading file : " + fileToLoad ) ;       
        ld.load( fileToLoad )                                ;        
    }

    
    private List<String> genericRequest(  String  request        ,
                                          List<String> variables ) throws  IOException  {
              
        QueryProcess  exec   =  QueryProcess.create(g) ;
        Mappings      map    =  null                   ;
         List<String> lines  = new ArrayList<> ()      ; 
         
        try {
            
             map = exec.query(request )   ;
             String res    = ""           ;
              
               for ( Mapping m : map ) {

                   for(String variable : variables )               {
                       String dt =  toStringDataType(m , variable) ;
                       if(dt == null ) continue                    ;
                       res +=  dt  +  " "                          ;
                   }
                   
                   if ( ! res.isEmpty() ) lines.add(res) ;
               }
                        
        } catch ( EngineException e )          {
             System.out.println ( " -------- " ) ;
             e.printStackTrace()               ;
             System.out.println ( " -------- " ) ;
        }

       return lines ;
    }
    
    private String toStringDataType ( Mapping m, String value ) {
          
        IDatatype dt = (IDatatype) m.getValue(value) ;
        if(dt == null) return null                   ;
           
        /*
        dt.intValue()        ;
        dt.doubleValue()     ;
        dt.booleanValue()    ;
        dt.stringValue()     ; 
        dt.getLabel()        ;
        dt.getDatatypeURI()  ;
        dt.getLang()         ;
        */
           
        if ( dt.getLang() != null )     {
           return "\"" + dt.getLabel()
                  .replaceAll("\"", "'") 
                  + "\"@" + dt.getLang() ;
        }
         
        if (dt.isURI() || dt.isBlank() )   {
           return "<" + dt.getLabel() +">" ;
        }
           
        return "\"" + dt.getLabel()
                        .replaceAll("\"", "'")    + 
                        "\"^^" + dt.getDatatype() ;
    }
    
    private static List<String> getVariables( String sparqlQuery ) {
        
        List<String> variables = new ArrayList<>() ;
            
        String[] tokens  = sparqlQuery.replaceAll("\\s+", " ")
                                      .replaceAll(Pattern.quote("("), " ")
                                      .replaceAll(Pattern.quote(")"), " ")
                                      .replaceAll(Pattern.quote("{"), " ")
                                      .replaceAll(Pattern.quote("}"), " ")
                                      .replaceAll(Pattern.quote(","), " ")
                                      .replaceAll(Pattern.quote(";"), " ")
                                      .replaceAll(Pattern.quote("."), " ")
                                      .split(" ") ;

        for(String token : tokens ) {
               
           if(token.equalsIgnoreCase("AS"))            {
               variables.remove(variables.size() - 1 ) ;
               continue                                ;
           }
               
           if( token.startsWith("?") && 
               !variables.contains(token )) {
                 variables.add(token)       ;
           }
               
           else if( token.equalsIgnoreCase("WHERE") ) break ;
        }
            
       return variables ;
    }

    private static String readFile( String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)));
    }
    
    private static Map<String, String> getPrefixes(String prefixFile ) throws IOException {
      
      if ( prefixFile == null ) return  null          ;
            
      Map<String, String > prefixes = new HashMap<>() ;
      
      try ( Stream<String> lines = Files.lines(Paths.get(prefixFile))) {
            
           lines.forEach ( line ->    { 
                String[] splited = line.trim().replaceAll(" + ", " ").split(" ") ;
                prefixes.put(splited[2], splited[1]) ;
            }) ;
      } catch( Exception ex ) {
           System.out.println(" ")                     ;
           System.out.println(" *** ERROR          " ) ;
           System.out.println(       ex.getMessage() ) ;
           System.out.println(" ")                     ;
           System.exit(0)                              ;
        } 
       
      return prefixes ;
    }
    
    private static void printEmptyMessageError( String variable , int numLine, int columnNumber ) {
        System.out.println("                                                                   " ) ;
        System.out.println(" *** ERROR                                                         " ) ;
        System.out.println("     No prefix found for variable : [ " + variable + " ] !         " ) ;
        System.out.println("     --> At CSV Line : " + numLine  + " // Column " + ++columnNumber ) ;              
        System.out.println("                                                                   " ) ;
    }
    
    private static void printNullMessageError( String uri , int numLine, int columnNumber ) {
        System.out.println("                                                                          " ) ;
        System.out.println(" *** ERROR                                                                " ) ;
        System.out.println("     No prefix assigned to URI : [ " + uri + " ] in the the prefix File ! " ) ;
        System.out.println("     --> At CSV Line : " + numLine  + " // Column " + ++columnNumber        ) ;        
        System.out.println("                                                                          " ) ; 
    }
    private static void printExceptionMessage( int numLine, int columnNumber ) {
        System.out.println("                                                                          " ) ;
        System.out.println(" *** EXCEPTION                                                            " ) ;
        System.out.println("     Error Occured during processing                                      " ) ;
        System.out.println("     --> At CSV Line : " + numLine  + " // Column " + ++columnNumber        ) ;        
        System.out.println("                                                                          " ) ; 
    }
    
    private static void printFileNotFoundExceptionAndExit( String path )                                  {
        System.out.println("                                                                          " ) ;
        System.out.println(" *** EXCEPTION                                                            " ) ;
        System.out.println("     Error Occured during processing                                      " ) ;
        System.out.println("     --> The File : [ " + path + " ] doesn't exist !                      " ) ;  
        System.out.println("                                                                          " ) ; 
        System.exit(0)                                                                                    ;
    }
    
    private static long totalLinesFileIgnoringEmptyLines ( String path ) {
    
     try {
            return Files.lines( Paths.get(path))
                        .filter( line -> line != null && ! line.trim().isEmpty() )
                        .count() ;
        } catch (IOException ex) {
            Logger.getLogger(Prefixer.class.getName()).log(Level.SEVERE, null, ex) ;
        }
     return 0 ;
    }
    
    
    private static String extract( Prefixer prefixer             ,
                                   String sparql                 ,
                                   String variable               ,
                                   List<String> variables        ,
                                   Map<String, String> prefixMap ,
                                   boolean full_uri              ,
                                   boolean enable_uri_brackets   ) throws Exception {
     
        Objects.requireNonNull ( prefixMap )            ;
        
        List<String> result = prefixer.genericRequest( sparql.replace("{0}",variable.trim() ) , variables ) ;

        if(result.size() > 1) {
            Set<String> collect = result.stream()
                                        .map( res -> res.trim().split(" ")[0] )
                                        .collect(toSet()) ;
            if(collect.size() > 1 ) {
                System.out.println(" Multiple Uris found for variable : [ "+ variable + " ] ")    ;
                collect.forEach( line -> System.err.println( "  - " + line.trim().split(" ")[0])) ;
                System.out.println(" + Retained --> " + result.get(0).trim().split(" ")[0])       ;
                System.out.println("                                                          " ) ;               
            }
        }
        
        if(result.isEmpty())    {
            return EMPTY_RESULT ;
        }
         
        if ( result.get(0).split(" ").length >= 2 ) {

            if( full_uri  ) {
                if( enable_uri_brackets ) {
                  return result.get(0).split(" ")[0].trim() ;
                }
                return result.get(0).split(" ")[0].trim().replace("<", "").replace(">", "") ;
            }
                
            String uri   = result.get(0).replace("<", "").replace(">", "").split("#")[0]    ;
                       
            String Class = result.get(0).split(" ")[1].split("\\^\\^")[0].replace("\"", "") ;

            
            String inputPrefix = prefixMap.get( uri + "#" ) ;

            if (inputPrefix == null) {
                return NULL_RESULT + uri ;
            }

            return inputPrefix + Class ;
        } else 
        {
            System.out.println(" + Query must Have Only Two VARIABLES ") ;
            System.out.println(" + You can re test without [ -query ] argument ") ;
            System.exit(2) ;
        }
        
        throw new IllegalArgumentException(" Error while extracting Prefix:Class for variable -> "+ variable ) ;
    }
     
    private static String treatLabel( Prefixer            prefixer            ,
                                      String              sparql              ,
                                      String              label               ,
                                      List<String>        sparqlVariables     , 
                                      Map<String, String> prefixMap           ,
                                      boolean             full_uri            ,
                                      boolean             enable_uri_brackets ) {
        try {
            return  extract( prefixer            ,
                             sparql              ,                               
                             label               ,
                             sparqlVariables     ,
                             prefixMap           ,
                             full_uri            ,
                             enable_uri_brackets ) ;
            
        } catch( Exception ex ) {
            ex.printStackTrace();
        }
        return null ;
    }
    
    private static boolean okResult( String var          , 
                                     String result       , 
                                     int    numLine      ,
                                     int    columnNumber )         {
        
       if( result == null ) {
           printExceptionMessage( numLine, columnNumber )          ; 
           return false ;
       }
       else if ( result.equals(EMPTY_RESULT ) ) {
           printEmptyMessageError(var, numLine, columnNumber )     ;
           return false ;
       }
       else  if ( result.equals(NULL_RESULT ) ) {
          printNullMessageError( result.replace( NULL_RESULT, "")  ,
                                 numLine, columnNumber)            ;
          return false ;
        } 
        
       return true ;
    }
    
    /* ENTRY MAIN */ 
    public static void main(String[] args) throws IOException, Exception  {

        List<Integer> columns       = new ArrayList<>() ;
        List<String> intra_seps     = new ArrayList<>() ;
                
        String  csv_separator       = null  ;
        String  ontologyFile        = null  ;
        String  outCsvFile          = null  ;
        String  prefixFile          = null  ;
        String  inCsvFile           = null  ;
        String  queryFile           = null  ;
        String  log                 = null  ;
            
        boolean full_uri            = false ;
        boolean priority            = false ;
        boolean enable_uri_brackets = false ;
        
        for (int i = 0; i < args.length; i++) {

            String token = args[i] ;

            switch (token) {

                case "-csv"                     :
                    inCsvFile = args[i + 1]     ; break ;                    
                case "-outCsv"                  :
                    outCsvFile = args[i + 1]    ; break ;                    
                case "-prefix"                  :
                      prefixFile = args[i + 1]  ; break ;
                case "-column"                  :
                    columns.add (
                            Integer.parseInt    (
                               args[i + 1] )
                            )                   ; break ;
                case "-csv_sep"                 :
                    csv_separator = args[i + 1] ; break ;              
                case "-intra_sep"               :
                    intra_seps.add(args[i + 1]) ; break ;              
                case "-ontology"                :
                    ontologyFile = args[i + 1]  ; break ;
                case "-log"                     :
                    log = args[i+1]             ; break ;               
                case "-priority"                :
                     priority = true            ; break ;               
                case "-query "                  :
                     queryFile = args[i + 1]    ; break ;
                case "-enable_full_uri"         :
                     full_uri = true            ; break ;
                case "-enable_uri_brackets"     :
                     enable_uri_brackets = true ; break ;
            }
        }

        Objects.requireNonNull(ontologyFile)  ;
        Objects.requireNonNull(inCsvFile)     ;
        Objects.requireNonNull(outCsvFile)    ;
        Objects.requireNonNull(csv_separator) ;
       
        if ( prefixFile == null && full_uri == false ) {
           System.out.println(" if -prefix is NULL, you have to " + 
                              " enable [ -enable_full_uri ] " )   ;
           System.exit(0) ;
        }
        
        System.out.println(" --------------------------                     " ) ;
        System.out.println(" + Info :                                       " ) ;
        System.out.println("   Ontology             : " + ontologyFile        ) ;
        System.out.println("   CSV                  : " + inCsvFile           ) ;
        System.out.println("   CSV OUT              : " + outCsvFile          ) ;
        System.out.println("   Prefix File          : " + prefixFile          ) ;
        System.out.println("   CSV_SEP              : " + csv_separator       ) ;
        System.out.println("   intra_seps           : " + intra_seps          ) ;
        System.out.println("   Full_URI             : " + full_uri            ) ;
        System.out.println("   enable_uri_brackets  : " + enable_uri_brackets ) ;
        System.out.println(" --------------------------                     " ) ;
 
        
        if( ! Writer.existFile( ontologyFile ) ) {
            printFileNotFoundExceptionAndExit(ontologyFile) ;
        }
        
        if( ! Writer.existFile( inCsvFile ) ) {
            printFileNotFoundExceptionAndExit(inCsvFile) ;
        }
        if( full_uri == false && ! Writer.existFile( prefixFile ) ) {
            printFileNotFoundExceptionAndExit(prefixFile) ;
        }
        
        if( ! Writer.existFile( inCsvFile ) ) {
            printFileNotFoundExceptionAndExit(inCsvFile) ;
        }
        
        System.setProperty("log", ( log == null || log.isEmpty() ) ? 
                                    "coreseLogs/logs.log" : log )  ;
         
        String _csv_separator =  csv_separator ;
        String currentSparql   ;
            
        boolean _full_uri = full_uri ;
        
        if(queryFile != null ) {
           currentSparql = readFile(queryFile) ;
        }
        else {
           currentSparql = SPARQL_SEARCH_IN_URI ;
        }
        
        Writer.deleteFile(outCsvFile) ;
        
        /* Load Graph */
         
        Prefixer              prefixer              =  getInstance( ontologyFile , true ) ;
        
        Map<String, String >  prefixMap             =  getPrefixes( prefixFile )          ;
        
        List<String>          sparqlVariables       =  getVariables( currentSparql )      ;
     
        final String          _outCsvFile           =  outCsvFile                         ;
       
        List<String>          collectedLines        =  new ArrayList<>()                  ;
        
        final boolean        _enable_uri_brackets  =  enable_uri_brackets                 ;
           
        try ( Stream<String> lines = Files.lines(Paths.get(inCsvFile)).skip(1))           {
                
                lines.forEach ( new Consumer<String>() {
                  
                  int numLine = 2 ; /* Ingore Header and Count from 1 */
                  
                  @Override
                  public void accept(String line) {
                  
                    if ( line == null || line.isEmpty() ) return          ;
                    
                    Map<Integer, String> treatedColumns = new HashMap<>() ;

                    columns.forEach( columnNumber ->    {

                      try {

                          int total_columns = line.replaceAll(" +", " " )
                                                  .split(_csv_separator).length ;
                          
                          if( columnNumber > total_columns - 1 ) {
                              
                              throw new IllegalArgumentException("\n \n "
                                        + "ArrayIndexOutOfBoundsException : "
                                        + " trying to acces the column ["
                                        + columnNumber 
                                        + "] When Max column = "
                                        + total_columns + "\n ") ;
                          }
                          
                          String column =  line.replaceAll(" +", " " )
                                               .split(_csv_separator)[columnNumber]   ;
                            
                          /* Ingore Empty Columns -> Valide */
                          
                          if( column.isEmpty()) return  ;
                              
                          String parser = getContainedSeparator( column, intra_seps ) ;

                          if( parser != null && ! parser.isEmpty() ) {
                                                            
                              String[] subColumnVariables = column.trim().split(parser) ;
                              
                              for( String var : subColumnVariables ) {
                               
                                  String treatLabel = treatLabel( prefixer                  ,
                                                                  currentSparql             ,
                                                                  var.replaceAll(" +", "" ) ,
                                                                  sparqlVariables           ,
                                                                  prefixMap                 ,
                                                                  _full_uri                 ,
                                                                  _enable_uri_brackets   )  ; 
                                  if( treatLabel == null              ||
                                      treatLabel.equals(EMPTY_RESULT) ||
                                      treatLabel.startsWith(NULL_RESULT)) {
                                  
                                        List<String> sparqlVars   = getVariables( SPARQL_SEARCH_IN_LABEL ) ;

                                         treatLabel = treatLabel( prefixer                  ,
                                                                  SPARQL_SEARCH_IN_LABEL    ,
                                                                  var.replaceAll(" +", " ") ,
                                                                  sparqlVars                ,
                                                                  prefixMap                 ,
                                                                 _full_uri                  ,
                                                                 _enable_uri_brackets     ) ;

                                         if( ! okResult(var, treatLabel, numLine, columnNumber )) {
                                             treatedColumns.put(  columnNumber , null )    ;
                                         }
                                         else {
                                             final String lm = treatLabel ;
                                             treatedColumns.computeIfPresent( columnNumber, 
                                                                              (k,v ) -> v + " " + parser + " " + lm ) ;
                                             treatedColumns.computeIfAbsent( columnNumber , key -> lm )               ;  
                                         }
                                  }
                                  else {
                                        final String lm = treatLabel ;
                                        treatedColumns.computeIfPresent( columnNumber, 
                                                                        (k,v ) -> v + " " + parser + " " + lm ) ;
                                        treatedColumns.computeIfAbsent( columnNumber , key -> lm )              ;
                                  }
                             }
                          } 
                          
                          else {

                              String treatLabel = treatLabel( prefixer                    ,
                                                              currentSparql               ,
                                                              column.replaceAll(" +", "") ,
                                                              sparqlVariables             ,
                                                              prefixMap                   ,    
                                                             _full_uri                    ,
                                                             _enable_uri_brackets       ) ; 
                              
                              if ( treatLabel == null              ||
                                   treatLabel.equals(EMPTY_RESULT) ||
                                   treatLabel.startsWith(NULL_RESULT)) {
                                  
                                  List<String> sparqlVars   = getVariables( SPARQL_SEARCH_IN_LABEL ) ;
                                  
                                  treatLabel = treatLabel( prefixer                ,
                                                           SPARQL_SEARCH_IN_LABEL  ,
                                                           column                  ,
                                                           sparqlVars              ,
                                                           prefixMap               ,           
                                                           _full_uri               ,
                                                           _enable_uri_brackets  ) ; 
                                                            
                                  if( ! okResult(column, treatLabel, numLine, columnNumber )) {
                                      treatedColumns.put(  columnNumber , null )      ;
                                   } else {
                                      treatedColumns.put( columnNumber , treatLabel ) ;       
                                  }                         
                              }
                              else {
                                  treatedColumns.put( columnNumber , treatLabel ) ;
                              }
                              
                          }
                          
                      } catch (Exception ex) {
                          Logger.getLogger(Prefixer.class.getName()).log(Level.SEVERE, null, ex);
                      }
                    }) ;

                    String[] splited = line.replaceAll(" +", " " ).split(_csv_separator) ;

                    if( ! treatedColumns.values().contains(null) ) {
                        String collectLine = IntStream.range(0, splited.length)
                                                      .mapToObj( i ->   { 
                                                                   return treatedColumns.get(i) != null ? 
                                                                   treatedColumns.get(i) : splited[i]   ;
                                                            })
                                                      .collect(Collectors.joining(_csv_separator))      ;
                        collectedLines.add(collectLine) ;
                    }
                   
                   numLine ++ ;
                          
                  }                                    
                }) ;
       }
       
       if( ! collectedLines.isEmpty() &&  
           ( totalLinesFileIgnoringEmptyLines( inCsvFile) -1 ) 
                                              == collectedLines.size() )       {
        
           Writer.checkFile( _outCsvFile                                     ) ;
           String header = Files.lines(Paths.get(inCsvFile)).findFirst().get() ;         
           Writer.writeTextFile(Arrays.asList(header), _outCsvFile           ) ;
         
           Writer.writeTextFile(collectedLines, _outCsvFile                  ) ;
           System.out.println( "                                           " ) ;
           System.out.println( " CSV Generated at path :"  + _outCsvFile     ) ;
           System.out.println( "                                           " ) ;
       }
       else {
           System.out.println( " No CSV Generated ! " ) ; 
           System.out.println( "                    " ) ;
       }
       
    }
   
}
