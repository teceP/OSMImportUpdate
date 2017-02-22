package inter2ohdm;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import static util.InterDB.NODETABLE;
import static util.InterDB.RELATIONMEMBER;
import static util.InterDB.RELATIONTABLE;
import static util.InterDB.WAYMEMBER;
import static util.InterDB.WAYTABLE;
import util.DB;
import util.SQLStatementQueue;

/**
 *
 * @author thsc
 */
public class IntermediateDB {
    private final boolean debug = false;
    protected final Connection sourceConnection;
    private final String schema;
    private boolean isNew;
    private boolean changed;
    private boolean deleted;
    private boolean has_name;
    private Date tstamp;
    
    IntermediateDB(Connection sourceConnection, String schema) {
        this.sourceConnection = sourceConnection;
        this.schema = schema;
    }
    
    protected String getIntermediateTableName(OSMElement element) {
        if(element instanceof OSMNode) {
            return(DB.getFullTableName(this.schema, NODETABLE));
        } else if(element instanceof OSMWay) {
            return(DB.getFullTableName(this.schema, WAYTABLE));
        } else {
            return(DB.getFullTableName(this.schema, RELATIONTABLE));
        } 
    }
    
    public void setOHDM_IDs(OSMElement element, String ohdmObjectIDString, 
            String ohdmGeomIDString) throws SQLException {
        
        if(element == null) return;
        
        if(ohdmObjectIDString == null && ohdmGeomIDString == null) return;
        
        /*
        UPDATE [waysTable] SET ohdm_id=[ohdmID] WHERE osm_id = [osmID];
        */
        SQLStatementQueue sq = new SQLStatementQueue(this.sourceConnection);
        sq.append("UPDATE ");
        
        sq.append(this.getIntermediateTableName(element));
        
        sq.append(" SET ");
        boolean parameterSet = false;
        if(ohdmObjectIDString != null) {
            sq.append("ohdm_object_id = ");
            sq.append(ohdmObjectIDString);
            parameterSet = true;
        }
        
        if(ohdmGeomIDString != null) {
            if(parameterSet) {
                sq.append(", ");
            }
            sq.append("ohdm_geom_id = ");
            sq.append(ohdmGeomIDString);
        }
        
        sq.append(" WHERE osm_id = ");
        sq.append(element.getOSMIDString());
        sq.append(";");
        sq.forceExecute();
    }
    
    void remove(OSMElement element) throws SQLException {
        SQLStatementQueue sq = new SQLStatementQueue(this.sourceConnection);
        
        /*
        remove entries which refer to that element
        */

        if(element instanceof OSMRelation) {
            // remove line from relationsmember
            sq.append("DELETE FROM ");

            sq.append(DB.getFullTableName(this.schema, RELATIONMEMBER));

            sq.append(" WHERE relation_id = ");
            sq.append(element.getOSMIDString());
            sq.append(";");
            sq.forceExecute();
        } else if(element instanceof OSMWay) {
            // remove line from relationsmember
            sq.append("DELETE FROM ");

            sq.append(DB.getFullTableName(this.schema, WAYMEMBER));

            sq.append(" WHERE way_id = ");
            sq.append(element.getOSMIDString());
            sq.append(";");
            sq.forceExecute();
        }
        
        /*
        DELETE FROM [table] WHERE osm_id = [osmID]
        */
        
        sq.append("DELETE FROM ");
        
        sq.append(this.getIntermediateTableName(element));
        
        sq.append(" WHERE osm_id = ");
        sq.append(element.getOSMIDString());
        sq.append(";");
        sq.forceExecute();
    }
    
    OSMWay addNodes2OHDMWay(OSMWay way) throws SQLException {
        // find all associated nodes and add to that way
        /* SQL Query is like this
            select * from nodes_table where osm_id IN 
            (SELECT node_id FROM waynodes_table where way_id = ID_of_way);            
        */ 
        SQLStatementQueue sql = new SQLStatementQueue(this.sourceConnection);

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
    
    
    ///////////////////////////////////////////////////////////////////////
    //                         factory methods                           //
    ///////////////////////////////////////////////////////////////////////
    
    private String osmIDString;
    private String classCodeString;
    private String sTags;
    private String ohdmObjectIDString;
    private String ohdmGeomIDString;
    private String memberIDs;
    private boolean valid;
    
    private void readCommonColumns(ResultSet qResult) throws SQLException {
        osmIDString = this.extractBigDecimalAsString(qResult, "osm_id");
        if(this.debug) {
            System.out.print(", " + osmIDString);
        }
        if(osmIDString.equalsIgnoreCase("1368193")) {
            int i = 42;
        }
        classCodeString = this.extractBigDecimalAsString(qResult, "classcode");
        sTags = qResult.getString("serializedtags");
        ohdmObjectIDString = this.extractBigDecimalAsString(qResult, "ohdm_object_id");
        ohdmGeomIDString = this.extractBigDecimalAsString(qResult, "ohdm_geom_id");
        valid = qResult.getBoolean("valid");
        
        this.isNew = qResult.getBoolean("new");
        this.changed = qResult.getBoolean("changed");
        this.deleted = qResult.getBoolean("deleted");
        this.has_name = qResult.getBoolean("has_name");
        
        this.tstamp = qResult.getDate("tstamp");

    }
    
    private String extractBigDecimalAsString(ResultSet qResult, String columnName) throws SQLException {
        BigDecimal bigDecimal = qResult.getBigDecimal(columnName);
        if(bigDecimal != null) {
            return bigDecimal.toString();
        }
        return null;
    }
    
    protected OSMRelation createOHDMRelation(ResultSet qResult) throws SQLException {
        // get all data to create an ohdm way object
        this.readCommonColumns(qResult);
        memberIDs = qResult.getString("member_ids");

        OSMRelation relation = new OSMRelation(this, osmIDString, 
                classCodeString, sTags, memberIDs, ohdmObjectIDString, 
                ohdmGeomIDString, valid, this.isNew, this.changed, this.deleted, 
                this.has_name, this.tstamp
        );
        
        return relation;
    }
    
    protected OSMWay createOHDMWay(ResultSet qResult) throws SQLException {
        this.readCommonColumns(qResult);
        String nodeIDs = qResult.getString("node_ids");

        OSMWay way = new OSMWay(this, osmIDString, classCodeString, sTags, 
                nodeIDs, ohdmObjectIDString, ohdmGeomIDString, valid, 
                this.isNew, this.changed, this.deleted, 
                this.has_name, this.tstamp
        );

        return way;
    }
    
    protected OSMNode createOHDMNode(ResultSet qResult) throws SQLException {
        this.readCommonColumns(qResult);
        String longitude = qResult.getString("longitude");
        String latitude = qResult.getString("latitude");
        
        OSMNode node = new OSMNode(this, osmIDString, classCodeString, sTags, 
                longitude, latitude, ohdmObjectIDString, ohdmGeomIDString, 
                valid, this.isNew, this.changed, this.deleted, 
                this.has_name, this.tstamp);

        return node;
    }
    
}
