
 package corese ;

 /**
 *
 * @author ryahiaoui
 */

 import java.util.List                                    ;
 import java.util.Arrays                                  ;
 import java.util.Objects                                 ;
 import java.io.IOException                               ;
 import java.nio.file.Files                               ;
 import java.nio.file.Paths                               ;
 import java.util.ArrayList                               ;
 import java.util.regex.Pattern                           ;
 import java.util.logging.Level                           ;
 import java.util.logging.Logger                          ;
 import java.util.stream.IntStream                        ;
 import java.util.stream.Collectors                       ;
 import fr.inria.edelweiss.kgtool.load.Load               ;
 import fr.inria.acacia.corese.api.IDatatype              ;
 import fr.inria.edelweiss.kgraph.core.Graph              ;
 import fr.inria.edelweiss.kgram.core.Mapping             ;
 import fr.inria.edelweiss.kgram.core.Mappings            ;
 import static java.util.stream.Collectors.toList         ;
 import fr.inria.edelweiss.kgraph.rule.RuleEngine         ;
 import fr.inria.edelweiss.kgtool.print.ResultFormat      ;
 import fr.inria.edelweiss.kgraph.query.QueryProcess      ;
 import fr.inria.acacia.corese.exceptions.EngineException ;


 public class Main {
      
        private static volatile Main _instance = null ;
                
        private static Graph        g   ;
        private static Load         ld  ;
        private static RuleEngine   re  ;

        enum FORMAT { TTL, XML , CSV }  ;
 
        static int loop  = 0            ;
 
        private static final String URI_VALIDATOR = 
                       "^((https?|ftp|file)://|(www\\.)|(<_:))[-a-zA-Z0-9+&@#/%?=~_|!:,.;µs%°]*[-a-zA-Z0-9+&@#/%=~_|]" ;
     
        private Main(){}

        public static Main getInstance( List<String> filesToLoad, boolean entailment ) {
                if (_instance == null) {
                        synchronized (Main.class) {
                                if (_instance == null) {
                                        _instance = new Main()                ;
                                        initialize (filesToLoad , entailment) ;
                                }
                        }
                }
                return _instance ;
        }
      
        private static void initialize( List<String> filesToLoad, boolean entailment ) {
          
            try {
                  g  = Graph.create(entailment) ;             
                  ld = Load.create(g)           ;
                  loadFiles( filesToLoad  )     ;
            }
            catch (Exception ex) {
                  ex.printStackTrace() ;
                  Throwable[] suppressed = ex.getSuppressed();
                  for(Throwable cause : suppressed ) {
                      System.err.println(cause.getMessage()) ;
                  }
                  System.out.println(" ") ;
            }
        }
        
        private static void loadFiles ( List<String> filesToLoad ) {
           
            for( String file : filesToLoad   )   {
                 loadFile( file ) ;
            }
        }
        
        private static void loadFile ( String fileToLoad ) {
           
            System.out.println(" Loading file : " + fileToLoad ) ;
            ld.load( fileToLoad )   ;
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
           
           if (dt.isURI() || dt.isBlank() )  {
             return "<" + dt.getLabel() +">" ;
           }
           
           return "\"" + dt.getLabel()
                           .replaceAll("\"", "'")    + 
                           "\"^^" + dt.getDatatype() ;
      }
      
      private void genericRequest(  String  request        ,
                                    List<String> variables ,
                                    String  outputFile     , 
                                    int     fragment       , 
                                    int     numChunk       ,
                                    int     numRequest     ,
                                    FORMAT  format         ,
                                    int     flushCount     ,
                                    boolean ignore_line_br ,
                                    String  extension
                                  ) throws  IOException    {
              
                QueryProcess exec      =  QueryProcess.create(g) ;
                Mappings     map       =  null                   ;
                
                try {
                        map = exec.query(request ) ;
                } catch (EngineException e) {
                        System.out.println(" -------- " ) ;
                          e.printStackTrace()             ;
                        System.out.println(" -------- " ) ;
                }

                if( format == FORMAT.TTL || format == FORMAT.CSV ) {
                    
                    List<String> lines = new ArrayList<> () ;

                    String res    = ""    ;
                    int    count  = 0     ;
                   
                    String currentFile    ;

                    currentFile =  getCurrentFile( outputFile , 
                                                   numChunk   ,
                                                   numRequest , 
                                                   fragment   , 
                                                   loop       ,
                                                   extension  ) ;
                    
                    Writer.checkFile( currentFile )           ;

                    for ( Mapping m : map ) {

                        for(String variable : variables )              {
                            String dt =  toStringDataType(m, variable) ;
                            if(dt == null ) continue                   ;
                            res +=  dt  +  " "                         ;
                        }
                        
                        /* Remove new Line On Demand */
                        if( ignore_line_br )                 {
                            res = res.replaceAll("\n", "  ") ;
                        }
                        
                        /* Ignore literal values */                        
                        if( format == FORMAT.CSV || isSubjectURIOrBlank(res) ) {
                            count ++                   ;
                            lines.add( res + " . " )   ;
                        }

                        if( fragment != 0 && count % fragment == 0  )    {
                            
                           if( ! lines.isEmpty() )  {
                               
                              Writer.writeTextFile(lines, currentFile )  ;
                              lines.clear()                              ;
                              currentFile =  getCurrentFile( outputFile  , 
                                                             numChunk    ,
                                                             numRequest  , 
                                                             fragment    ,
                                                             ++loop      ,
                                                             extension ) ;
                              Writer.checkFile( currentFile )            ;
                           }
                        }

                        if( lines.size() % flushCount == 0 )          {
                            Writer.writeTextFile(lines, currentFile ) ;
                            lines.clear()                             ;
                        }

                        res = "" ;
                    }

                    if(!lines.isEmpty()) {
                       Writer.writeTextFile( lines , currentFile) ;
                       lines.clear()                              ;
                    }

                    /* Delete last file if empty */
                    if(Files.lines( Paths
                            .get(currentFile)).count() == 0 )    {
                       Paths.get(currentFile).toFile().delete()  ;
                    } 
                }
                
                else if (format == FORMAT.XML )               {
                    
                    Writer.checkFile( outputFile )            ;
                    ResultFormat f = ResultFormat.create(map) ;
                    Writer.writeTextFile ( 
                          Arrays.asList(f.toString()) , 
                                                 outputFile ) ;
                }
                
                loop ++ ;
                
                /*
                   print Turtle Result
                   TripleFormat f = TripleFormat.create(g, true);
                   System.out.println(f);
                */
        }
         
          private static boolean isSubjectURIOrBlank( String path )   {
          
          Objects.requireNonNull( path , 
                " subject "                              +
                " parameter should not be null " )       ;
          if(path.isEmpty()) return false                ;
          
          String subject = path.split(" ")[0]            ;
          
          if( subject.startsWith("<")  
              && subject.endsWith(">") )  {
              return subject.substring(1, 
                            subject.lastIndexOf(">"))
                                 .matches(URI_VALIDATOR) ;
          }
          return false ;
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
               
               else if( token.equalsIgnoreCase("where") ) break ;
            }
            
          return variables ;
        }
        
        private static boolean isSelectQuery ( String query ) {
         
            return query.trim()
                        .replaceAll("\\s+", " ")
                        .toLowerCase()
                        .contains("select ")   ;
        }
        
        private static String getCurrentFile(  String outFile    , 
                                               int    numChunk   ,
                                               int    numRequest , 
                                               int    fragment   ,
                                               int    loop       ,
                                               String ext      ) {

            String  folder       = Writer.getFolder ( outFile )          ;
            
            String  filefullName = outFile.equals(folder)                ?
                                   "data"                                : 
                                   Writer.getfileName(outFile )          ;
            
            String fileName      = Writer.getFileWithoutExtension ( 
                                                          filefullName ) ;
                    
            String  ext_f        = Writer.getFileExtension(filefullName) ; 
            
            if( ext_f == null || ext_f.isEmpty() )  {
                
                if ( ext == null || ext.isEmpty() ) {
                     ext = ".ttl"                   ;
                }
                else if( ! ext.startsWith("."))     {
                    ext = "." + ext                 ;
                }
                
                ext_f = ext                         ;
            }
            
            if ( fragment <= 0 ) {
                
                return  printInfoFile ( 
                        folder     +  fileName +  "_corese_req_"  + 
                        numRequest + "_chunk_" +  numChunk        +
                        ext_f   )                                 ;
            }
        
            else {
                   return  printInfoFile (
                           folder     +  fileName + "_corese_req_" + 
                           numRequest + "_chunk_" +  numChunk      + 
                           "_idx_"    + loop      +  ext_f       ) ; 
                  }
        }
        
        private static List<String> listFiles (String fileOrFolder ) {

            if( ! Files.isDirectory(Paths.get(fileOrFolder))) {
                  return Arrays.asList(fileOrFolder) ;
            }
            try {
                return  Files.list(Paths.get(fileOrFolder))
                             .filter(Files::isRegularFile)
                             .map( f -> f.toAbsolutePath().toString())
                             .collect(toList()) ;
            } catch (IOException ex ) {
                Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex ) ;
            }
            return null ;
        } 

        
        private static List<String> listFoldersOrFiles(List<String> ttl ) {
          return ttl.stream()
                    .map( Main::listFiles )
                    .flatMap(List::stream)
                    .collect(toList()) ;
        }      

        private static void printStartLine() {
          System.out.println( " ***************************"
                              + "************************** " )  ;
        }
        private static void printSnakeLine() {
           System.out.println("----------------------------"
                              + "----------------------"      )  ;
        }
        
        private static String printInfoFile ( String pathFile )      {
            System.out.println("  --> Generate File : " + pathFile ) ;
            return pathFile                                          ;
        }
  
        private static String removeDoubleQuotes(String arg)                 {
           if( ( arg.trim().startsWith("\"") && arg.trim().endsWith("\"") ) ||
               ( arg.trim().startsWith("'") && arg.trim().endsWith("'") ) )  {
               return arg.substring( 1, arg.length() - 1 ) ;
           }
           return arg ;
        }

        public static void main( String[] args) throws IOException    {
            
            if( args.length < 6 ) {
                System.out.println(" Nombre paramètres incomplet ! ") ;
                return ;
            }
            
            List<String> owls         = new ArrayList<>() ;
            List<String> ttl          = new ArrayList<>() ;
            List<String> queries      = new ArrayList<>() ;
            List<String> outs         = new ArrayList<>() ;
            List<Integer> fragments   = new ArrayList<>() ;
            List<String> formats      = new ArrayList<>() ;
            String       log          = null              ;
            boolean      entailment   = false             ;
            int          peek         = 0                 ;
            int          flushCount   = 10_000            ;
            boolean ignore_line_break = false             ;
            String  extension         = ""                ;

            for ( int i = 0 ; i < args.length ; i++ )     {
                
                String token = args[i] ;
                
                switch(token) {
                    
                    case "-owl"               : owls.add(args[i+1])                          ;
                                                break ;
                    case "-ttl"               : ttl.add(args[i+1])                           ;
                                                break ;
                    case "-out"               : outs.add(args[i+1])                          ;
                                                break ;
                    case "-q"                 : queries.add(removeDoubleQuotes (args[i+1]))  ;
                                                break ;
                    case "-f"                 : fragments.add(Integer.parseInt( args[i+1]) ) ;
                                                break ;
                    case "-e"                 : entailment = true                            ;
                                                break ;
                    case "-F"                 : formats.add(args[i+1].toUpperCase())         ;
                                                break ;
                    case "-log"               : log = args[i+1]                              ;
                                                break ;
                    case "-peek" :              peek = Integer.parseInt(args[i+1])           ;
                                                break ;
                    case "-flushCount"        : flushCount = Integer.parseInt(args[i+1])     ;
                                                break ;                                   
                    case "-ignore_line_break" : ignore_line_break = true                     ;
                                                break ;
                    case "-extension"         : extension = args[i+1]                        ;
                                                break ;
                }
            }
            
            System.out.println( " Ontologies ( owl )   : " )              ;
            owls.stream().forEach( e -> System.out.println("  " + e ) )   ;
            System.out.println(" Semantic Data ( ttl ) : " )              ;
            ttl.stream().forEach( e ->  System.out.println("  " + e ) )   ;
            System.out.println( "                                   " )   ;
            
            if( owls.isEmpty() || ttl.isEmpty() ) {
                 System.out.println(" owl or ttl parameter is empty ! " ) ;
                 return                                                   ;
            }
            
            if( ( queries.isEmpty() ) ) {
                 System.out.println("  Error nbr parameters !! " )        ;
                 return                                                   ;
            }
            
            if (   queries.size() != outs.size()      || 
                 ( queries.size() != fragments.size() || 
                   queries.size() != formats.size() )
               )                                       {
                 
                 System.out.println(" Bad size List Queries " +
                                    " /Outs / Fragment !! ")  ;
                 return ;
            }
            System.out.println("                                          ") ;
            System.out.println(" FORMAT            : " + formats           ) ;
            System.out.println(" FRAGMENT          : " + fragments         ) ;
            System.out.println(" PEEK              : " + peek              ) ;
            System.out.println(" FLUSH_COUNT       : " + flushCount        ) ;
            System.out.println(" EXTENSION         : " + extension         ) ;
            System.out.println(" IGNORE_LINE_BREAK : " + ignore_line_break ) ;
            System.out.println(" XMS               : " + ignore_line_break ) ;
            System.out.println(" XMX               : " + ignore_line_break ) ;
            System.out.println("                                          ") ;
         
            List<String> entryFiles = new ArrayList<>()  ; 
            
            entryFiles.addAll(listFoldersOrFiles(ttl))   ;
           
            System.setProperty("log", ( log == null || log.isEmpty() )  ? 
                                         "coreseLogs/logs.log" : log )  ;
            
            long startTime = System.currentTimeMillis()  ;  
           
            int listSize  = entryFiles.size()                    ;
            int chunkSize = peek > 0 ? peek : entryFiles.size()  ;
            
            List<List<String>> chunkedList = new ArrayList<>()   ;
            
            if( chunkSize > 0 ) {
                
              chunkedList = IntStream.range( 0 , ( listSize - 1 ) / chunkSize + 1 )
                                     .mapToObj( i -> entryFiles
                                     .subList ( i *= chunkSize ,
                                                listSize - chunkSize >= i ? i + chunkSize : listSize))
                                     .collect(Collectors.toList()) ;
            }
            
            int numChunk = 0 ;

            printStartLine()          ;     
            System.out.println( " " ) ;
                
            /* Load Graph */
            Main instance = Main.getInstance( owls , entailment ) ;
              
            System.out.println( " --- " )                         ; 
              
            if(chunkedList.isEmpty() )  {
                
              /* Load Only Ontology */
               traverse( instance          ,
                         queries           , 
                         formats           ,  
                         outs              ,
                         fragments         ,
                         numChunk          ,
                         flushCount        ,
                         ignore_line_break ,
                         extension       ) ;
               
               ld.reset()              ;
               g.clean ()              ;
               loop   = 0              ; 
               System.out.println(" ") ;
            }
            
            else  {
                
                /* Load File by Chunk */
                
                for( List<String> chunk : chunkedList )  {

                  loadFiles (chunk )                     ;

                  traverse( instance          ,
                            queries           , 
                            formats           ,  
                            outs              ,
                            fragments         ,
                            numChunk ++       ,
                            flushCount        ,
                            ignore_line_break ,
                            extension       ) ;

                  ld.reset()              ;
                  g.clean ()              ;
                  loop   = 0              ;             
                  System.out.println(" ") ;

                }
            }
            
            printStartLine() ;      
            System.out.println(" ")                                            ;
            long executionTime = System.currentTimeMillis() - startTime        ;
            System.out.println(" Elapsed seconds : " + 
                                                 executionTime / 1000 + " s" ) ; 
            System.out.println(" ")                                            ;
                   
        }
        
        private static void traverse( Main          instance       ,
                                      List<String>  queries        ,
                                      List<String>  formats        ,
                                      List<String>  outs           ,
                                      List<Integer> fragments      ,
                                      int           numChunk       ,
                                      int           flushCount     ,
                                      boolean       ignore_line_br ,
                                      String        extension      ) throws IOException {
            
          /* Travers Queries */
         for( int numQuery = 0 ; numQuery < queries.size() ; numQuery++ )    {
                   
              List<String> variables = getVariables(queries.get(numQuery) )  ;
              FORMAT       format    = FORMAT.valueOf(formats.get(numQuery)) ;
              String       query     =  queries.get(numQuery)                ;
              String       out       = outs.get(numQuery)                    ;
              int          fragment  = fragments.get(numQuery)               ;
                   
              if ( isSelectQuery(queries.get(numQuery))                     ||
                   ( ! isSelectQuery(queries.get(numQuery))                 &&
                      ( ! format.toString().toLowerCase().
                           equals( FORMAT.TTL.toString().toLowerCase())
                      )
                   ) 
                 )                                                    {
                    
                   if( format == FORMAT.TTL && variables.size() != 3 ) {
                       System.out.print  (" Query must have exactly 3 variables ( subject, predicate, object ) " ) ;
                       System.out.println(" when Tuttle format is activated (-ttl ) " )                            ;
                       System.out.println(" See https://www.w3.org/TR/turtle  " )                                  ;
                       System.out.println(" Or try without -ttl parameter " )                                      ;
                       return ;
                   }

                   printSnakeLine() ;

                   System.out.println(" + Executing query :  " + query           ) ;
                   System.out.println(" + FRAGMENT        :  " + fragment        ) ;
                   System.out.println(" + Out             :  " + out             ) ;
                   System.out.println("                                       " ) ;

                   instance.genericRequest( queries.get(numQuery)   ,
                                            variables               ,
                                            outs.get(numQuery)      ,
                                            fragments.get(numQuery) ,
                                            numChunk                ,
                                            numQuery                ,
                                            format                  ,
                                            flushCount              ,
                                            ignore_line_br          ,
                                            extension             ) ;

              }
              else {
                    System.out.println(queries.get(numQuery) + " \n " +
                    " Not supported yet. Only Select Queries for the moment ! " )  ;
            }
         }
        
        }
 }

