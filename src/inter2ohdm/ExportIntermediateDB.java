package inter2ohdm;

import java.io.PrintStream;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import util.InterDB;
import static util.InterDB.NODETABLE;
import static util.InterDB.RELATIONMEMBER;
import static util.InterDB.RELATIONTABLE;
import static util.InterDB.WAYMEMBER;
import static util.InterDB.WAYTABLE;
import util.DB;
import util.OHDM_DB;
import util.SQLStatementQueue;
import util.Util;

/**
 *
 * @author thsc
 */
public class ExportIntermediateDB extends IntermediateDB {
    private final Importer importer;
    
    private final String schema;
    
    private int printEra = 0;
    private final static int PRINT_ERA_LENGTH = 10000;
    private static final int DEFAULT_STEP_LEN = 1000;

    // for statistics
    private long number;
    private String nodesTableEntries = "?";
    private String waysTableEntries = "?";
    private String relationsTableEntries = "?";
    private long numberCheckedNodes = 0;
    private long numberCheckedWays = 0;
    private long numberCheckedRelations = 0;
    private long numberImportedNodes = 0;
    private long numberImportedWays = 0;
    private long numberImportedRelations = 0;
    private long historicInfos = 0;
    private final long startTime;
    
    static final int NODE = 0;
    static final int WAY = 1;
    static final int RELATION = 2;
    private int steplen;
    
    ExportIntermediateDB(Connection sourceConnection, String schema, Importer importer, int steplen) {
        super(sourceConnection, schema);
        
        this.startTime = System.currentTimeMillis();
        this.number = 0;
        
        this.schema = schema;
        this.importer = importer;
        
        if(steplen < 1) {
            this.steplen = DEFAULT_STEP_LEN;
        }
        
        this.steps = new BigDecimal(this.steplen);
    }

    private BigDecimal initialLowerID;
    private BigDecimal initialUpperID;
    private BigDecimal initialMaxID;
    private final BigDecimal steps;
    
    private String calculateInitialIDs(SQLStatementQueue sql, String tableName) {
        // first: figure out min and max osm_id in nodes table
        
        String resultString = "unknown";
        
        try {
            sql.append("SELECT count(id), min(id), max(id) FROM ");
            sql.append(DB.getFullTableName(this.schema, tableName));
            sql.append(";");

            ResultSet result = sql.executeWithResult();
            result.next();
            
            BigDecimal lines = result.getBigDecimal(1);
            BigDecimal minID = result.getBigDecimal(2);
/*
            sql.append("SELECT max(id) FROM ");
            sql.append(DB.getFullTableName(this.schema, tableName));
            sql.append(";");

            result = sql.executeWithResult();
            result.next();
*/            
            this.initialMaxID = result.getBigDecimal(3);

            this.initialLowerID = minID.subtract(new BigDecimal(1));
            this.initialUpperID = minID.add(this.steps);
            
            resultString = lines.toPlainString();
        }
        catch(SQLException se) {
            Util.printExceptionMessage(se, sql, "when calculating initial min max ids for select of nodes, ways or relations", false);
        }
        
        return resultString;
    }
    
    void processNode(ResultSet qResult, SQLStatementQueue sql, boolean importUnnamedEntities) {
        OSMNode node = null;
        try {
            node = this.createOHDMNode(qResult);
            this.currentElement = node;
            
            if(node.getOSMIDString().equalsIgnoreCase("20246240")) {
                int i = 42;
            }
            
            this.numberCheckedNodes++;

            if(node.isConsistent()) {
                // now process that stuff
                if(this.importer.importNode(node, importUnnamedEntities)) {
                    this.numberImportedNodes++;
                }

                if(this.importer.importPostProcessing(node, importUnnamedEntities)) {
                    this.historicInfos++;
                }
            } else {
                this.printError(System.err, "node not consistent:\n" + node);
            }
        }
        catch(SQLException se) {
            System.err.println("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++");
            System.err.println("exception when handling node osm_id: " + node.getOSMIDString());
            Util.printExceptionMessage(se, sql, "failure when processing node.. non fatal", true);
            System.err.println("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++");
        }
    }
    
    void printError(PrintStream p, String s) {
        p.println(s);
        p.println("-------------------------------------");
    }
    
    void processWay(ResultSet qResult, SQLStatementQueue sql, boolean importUnnamedEntities) {
        OSMWay way = null;
        try {
            way = this.createOHDMWay(qResult);
            this.currentElement = way;
            
            if(way.getOSMIDString().equalsIgnoreCase("4557344")) {
                int i = 42;
            }

//            if(!way.isPart() && way.getName() == null) notPartNumber++;

            this.addNodes2OHDMWay(way);
            
            this.numberCheckedWays++;

            if(way.isConsistent()) {
                // process that stuff
                if(this.importer.importWay(way, importUnnamedEntities)) {
                    this.numberImportedWays++;
                }

                if(this.importer.importPostProcessing(way, importUnnamedEntities)) {
                    this.historicInfos++;
                }
            } else {
                this.printError(System.err, "way not consistent:\n" + way);
            }
        }
        catch(SQLException se) {
            System.err.println("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++");
            System.err.println("exception when processing way: " + way);
            Util.printExceptionMessage(se, sql, "failure when processing way.. non fatal", true);
            System.err.println("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++");
        }
    }
    
    void processRelation(ResultSet qResult, SQLStatementQueue sql, boolean importUnnamedEntities) {
        OSMRelation relation = null;
        
        try {
            relation = this.createOHDMRelation(qResult);
            
            String r_id = relation.getOSMIDString();
            if(r_id.equalsIgnoreCase("4451529")) {
                int i = 42;
            }

            this.currentElement = relation;

            // find all associated nodes and add to that relation
            sql.append("select * from ");
            sql.append(DB.getFullTableName(this.schema, RELATIONMEMBER));
            sql.append(" where relation_id = ");            
            sql.append(relation.getOSMIDString());
            sql.append(";");  

            ResultSet qResultRelation = sql.executeWithResult();

            boolean relationMemberComplete = true; // assume we find all member

            while(qResultRelation.next()) {
                String roleString =  qResultRelation.getString("role");

                // extract member objects from their tables
                BigDecimal id;
                int type = -1;

                sql.append("SELECT * FROM ");

                id = qResultRelation.getBigDecimal("node_id");
                if(id != null) {
                    sql.append(DB.getFullTableName(this.schema, NODETABLE));
                    type = OHDM_DB.POINT;
                } else {
                    id = qResultRelation.getBigDecimal("way_id");
                    if(id != null) {
                        sql.append(DB.getFullTableName(this.schema, WAYTABLE));
                        type = OHDM_DB.LINESTRING;
                    } else {
                        id = qResultRelation.getBigDecimal("member_rel_id");
                        if(id != null) {
                            sql.append(DB.getFullTableName(this.schema, RELATIONTABLE));
                            type = OHDM_DB.RELATION;
                        } else {
                            // we have a serious problem here.. or no member
                        }
                    }
                }
                sql.append(" where osm_id = ");
                sql.append(id.toString());
                sql.append(";");

                // debug stop
                if(id.toString().equalsIgnoreCase("245960580")) {
                    int i = 42;
                }

                ResultSet memberResult = sql.executeWithResult();
                if(memberResult.next()) {
                    // this call can fail, see else branch
                    OSMElement memberElement = null;
                    switch(type) {
                        case OHDM_DB.POINT: 
                            memberElement = this.createOHDMNode(memberResult);
                            break;
                        case OHDM_DB.LINESTRING:
                            memberElement = this.createOHDMWay(memberResult);
                            break;
                        case OHDM_DB.RELATION:
                            memberElement = this.createOHDMRelation(memberResult);
                            break;
                    }
                    relation.addMember(memberElement, roleString);
                } else {
                    /* this call can fail
                    a) if this program is buggy - which is most likely :) OR
                    b) intermediate DB has not imported whole world. In that
                    case, relation can refer to data which are not actually 
                    stored in intermediate db tables.. 
                    in that case .. remove whole relation: parts of it are 
                    outside our current scope
                    */
//                            System.out.println("would removed relation: " + relation.getOSMIDString());
//                    debug_alreadyPrinted = true;
                    //relation.remove();
                    relationMemberComplete = false; 
                }
                memberResult.close();

                if(!relationMemberComplete) break;
            }
            
            this.numberCheckedRelations++;
            
            if(relation.isConsistent()) {
                // process that stuff
                if(relationMemberComplete && this.importer.importRelation(relation, importUnnamedEntities)) {
                    this.numberImportedRelations++;

                    if(this.importer.importPostProcessing(relation, importUnnamedEntities)) {
                        this.historicInfos++;
                    }

                } 
            } else {
                this.printError(System.err, "inconsistent relation\n" + relation);
            }
        }
        catch(SQLException se) {
            System.err.println("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++");
            System.err.println("relation osm_id: " + relation.getOSMIDString());
            Util.printExceptionMessage(se, sql, "failure when processing relation.. non fatal", true);
            System.err.println("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++");
        }
    }
    
    void processNodes(SQLStatementQueue sql, boolean namedEntitiesOnly) {
        this.processElements(sql, NODE, namedEntitiesOnly);
    }
    
    void processWays(SQLStatementQueue sql, boolean namedEntitiesOnly) {
        this.processElements(sql, WAY, namedEntitiesOnly);
    }
    
    void processRelations(SQLStatementQueue sql, boolean namedEntitiesOnly) {
        this.processElements(sql, RELATION, namedEntitiesOnly);
    }
    
    void processElements(SQLStatementQueue sql, int elementType, boolean namedEntitiesOnly) {
        String elementTableName = null;
        switch(elementType) {
            case NODE:
                elementTableName = InterDB.NODETABLE;
                break;
            case WAY:
                elementTableName = InterDB.WAYTABLE;
                break;
            case RELATION:
                elementTableName = InterDB.RELATIONTABLE;
                break;
        }
        
        String elementsNumberString = this.calculateInitialIDs(sql, elementTableName);
        
        switch(elementType) {
            case NODE:
                this.nodesTableEntries = elementsNumberString;
                break;
            case WAY:
                this.waysTableEntries = elementsNumberString;
                break;
            case RELATION:
                this.relationsTableEntries = elementsNumberString;
                break;
        }
        
        // first: figure out min and max osm_id in nodes table
        BigDecimal lowerID = this.initialLowerID;
        BigDecimal upperID = this.initialUpperID;
        BigDecimal maxID = this.initialMaxID;
            
        try {
            this.printStarted(elementTableName);
            this.era = 0; // start new element type - reset for statistics
            do {
                sql.append("SELECT * FROM ");
                sql.append(DB.getFullTableName(this.schema, elementTableName));
                sql.append(" where id <= "); // including upper
                sql.append(upperID.toString());
                sql.append(" AND id > "); // excluding lower 
                sql.append(lowerID.toString());
                sql.append(" AND classcode != -1 "); // excluding untyped entities 
                if(namedEntitiesOnly) {
                    sql.append(" AND serializedtags like '%004name%'"); // entities with a name
                }
                sql.append(";");
                ResultSet qResult = sql.executeWithResult();
                
                while(qResult.next()) {
                    this.number++;
                    this.printStatistics();
                    this.processElement(qResult, sql, elementType, namedEntitiesOnly);
                }
                
                // next bulk of data
                lowerID = upperID;
                upperID = upperID.add(steps);
                
                if(upperID.compareTo(initialMaxID) == 1 && lowerID.compareTo(initialMaxID) == -1) {
                    upperID = initialMaxID; // last round
                }
            } while(!(upperID.compareTo(initialMaxID) == 1));
        } 
        catch (SQLException ex) {
            // fatal exception.. do not continue
            Util.printExceptionMessage(ex, sql, "when selecting nodes/ways/relation", false);
        }
        this.printFinished(elementTableName);
    }
        
    private void printExceptionMessage(Exception ex, SQLStatementQueue sql, OSMElement element) {
        if(element != null) {
            System.err.print("inter2ohdm: exception when processing ");
            if(element instanceof OSMNode) {
                System.err.print("node ");
            }
            else if(element instanceof OSMWay) {
                System.err.print("way ");
            }
            else {
                System.err.print("relation ");
            }
            System.err.print("with osm_id = ");
            System.err.println(element.getOSMIDString());
        }
        System.err.println("inter2ohdm: sql request: " + sql.toString());
        System.err.println(ex.getLocalizedMessage());
        ex.printStackTrace(System.err);
    }
    
    @Override
    OSMWay addNodes2OHDMWay(OSMWay way) throws SQLException {
        // find all associated nodes and add to that way
        /* SQL Query is like this
            select * from nodes_table where osm_id IN 
            (SELECT node_id FROM waynodes_table where way_id = ID_of_way);            
        */ 
        SQLStatementQueue sql = new SQLStatementQueue(this.sourceConnection);

        // TODO: replace join with enumeration from member id in table ways itself..
        sql.append("select * from ");
        sql.append(DB.getFullTableName(this.schema, NODETABLE));
        sql.append(" where osm_id IN (SELECT node_id FROM ");            
        sql.append(DB.getFullTableName(this.schema, WAYMEMBER));
        sql.append(" where way_id = ");            
        sql.append(way.getOSMIDString());
        sql.append(");");  

        ResultSet qResultNode = sql.executeWithResult();

        while(qResultNode.next()) {
            OSMNode node = this.createOHDMNode(qResultNode);
            way.addNode(node);
        }
        
        qResultNode.close();
        
        return way;
    }
    
    private final String progressSign = "*";
    private int progresslineCount = 0;
    private long era = 0;

    private void printStarted(String what) {
        System.out.println("--------------------------------------------------------------------------------");
        System.out.print("Start importing ");
        System.out.println(what);
        System.out.println(this.getStatistics());
        System.out.println("--------------------------------------------------------------------------------");
    }
    
    private void printFinished(String what) {
        System.out.println("\n--------------------------------------------------------------------------------");
        System.out.print("Finished importing ");
        System.out.println(what);
        System.out.println(this.getStatistics());
        System.out.println("--------------------------------------------------------------------------------");
    }
    
    public String getStatistics() {
        StringBuilder sb = new StringBuilder();
        
        sb.append("tablesize: ");
        sb.append("n:");
        sb.append(Util.setDotsInStringValue(this.nodesTableEntries));
        sb.append(",w:");
        sb.append(Util.setDotsInStringValue(this.waysTableEntries));
        sb.append(",r:");
        sb.append(Util.setDotsInStringValue(this.relationsTableEntries));
        sb.append("\n");
        
        sb.append("linesread:");
        sb.append(Util.getValueWithDots(this.number));
        sb.append(" | read steps: " + Util.getValueWithDots(this.steplen));
        sb.append("\n");
        
        sb.append("checked:  ");
        sb.append(Util.getValueWithDots(this.numberCheckedNodes + this.numberCheckedWays + this.numberCheckedRelations));
        sb.append(" (n:");
        sb.append(Util.getValueWithDots(this.numberCheckedNodes));
        sb.append(",w:");
        sb.append(Util.getValueWithDots(this.numberCheckedWays));
        sb.append(",r:");
        sb.append(Util.getValueWithDots(this.numberCheckedRelations));
        sb.append(")\n");
        
        sb.append("imported: ");
        sb.append(Util.getValueWithDots(this.numberImportedNodes + this.numberImportedWays + this.numberImportedRelations));
        sb.append(" (n:");
        sb.append(Util.getValueWithDots(this.numberImportedNodes));
        sb.append(",w:");
        sb.append(Util.getValueWithDots(this.numberImportedWays));
        sb.append(",r:");
        sb.append(Util.getValueWithDots(this.numberImportedRelations));
        sb.append(") ");
        
        sb.append("historic: ");
        sb.append(Util.getValueWithDots(this.historicInfos));
        sb.append(" | elapsed: ");
        sb.append(Util.getElapsedTime(this.startTime));
        
        return sb.toString();
    }
    
    private long p = 0;
    private static final int P_MAX = 100;
    
    private void printStatistics() {
        // show little progress...
        if(++p % P_MAX == 0) {
            System.out.print(".");
        }
        
        // line break
        if(p % (P_MAX * 50) == 0) { // 50 signs each line
            System.out.println(".");
            // stats
            if(p % (P_MAX * 500) == 0) { // after ten lines
                System.out.println(Util.getValueWithDots(p * this.steplen * P_MAX * 500) + " lines read");
            }
        }
        
        // show big steps
        if(++this.printEra >= PRINT_ERA_LENGTH) {
            this.printEra = 0;
            System.out.println("\n" + this.getStatistics());
        }
    }
    
    OSMElement currentElement = null;

    void processElement(ResultSet qResult, SQLStatementQueue sql, int elementType, boolean importUnnamedEntities) {
        this.currentElement = null;
        try {
            switch(elementType) {
                case NODE:
                    this.processNode(qResult, sql, importUnnamedEntities);
                    break;
                case WAY:
                    this.processWay(qResult, sql, importUnnamedEntities);
                    break;
                case RELATION:
                    this.processRelation(qResult, sql, importUnnamedEntities);
                    break;
            }
        }
        catch(Throwable t) {
            System.err.println("---------------------------------------------------------------------------");
            System.err.print("was handling a ");
            switch(elementType) {
                case NODE:
                    System.err.println("NODE ");
                    break;
                case WAY:
                    System.err.println("WAY ");
                    break;
                case RELATION:
                    System.err.println("RELATION ");
                    break;
            }
            if(currentElement != null) {
                System.err.println("current element osm id: " + this.currentElement.getOSMIDString());
            } else {
                System.err.println("current element is null");
            }
            Util.printExceptionMessage(t, sql, "uncatched throwable when processing element from intermediate db", true);
            System.err.println("---------------------------------------------------------------------------");
        }
    }
}
