/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package water.web;

import java.util.Properties;

/**
 *
 * @author peta
 */
public class GetQuery extends H2OPage {
  static final String html =
            "<p>Select the key you want to get (please note that you can only get user allowed keys):</p>"
          + "<form class='well form-inline' action='Get'>"
          + "  <input type='text' class='input-small span8' placeholder='key' name='Key' id='Key'>"
          + "  <button type='submit' class='btn btn-primary'>Get</button>"
          + "</form> "
          ;

  @Override protected String serve_impl(Properties args) {
    return html;
  }
  
  
  
  
}
