package co.edu.upb.dfsha.node.config;

import org.springframework.amqp.support.converter.JacksonJavaTypeMapper;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.amqp.support.converter.SimpleMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Mensajes AMQP en JSON, igual que el servidor. No se declaran colas ni exchanges: el usuario del
 * nodo no tiene permiso "configure"; toda la topología la crea definitions.json.
 */
@Configuration
public class AmqpConfig {

    /** Convierte los eventos que publica el nodo a JSON. */
    @Bean
    @Primary
    public MessageConverter conversorMensajes() {
        var converter = new JacksonJsonMessageConverter();
        converter.setTypePrecedence(JacksonJavaTypeMapper.TypePrecedence.INFERRED);
        return converter;
    }

    /**
     * Para los comandos que llegan del servidor: entrega el cuerpo sin convertir. El servidor los
     * envía con la cabecera __TypeId__ de sus propias clases (co.edu.upb.dfsha.server...), que no
     * existen en el nodo; Jackson los rechazaría. CommandParser lee y valida el JSON a mano.
     */
    @Bean
    public MessageConverter conversorComandos() {
        return new SimpleMessageConverter();
    }
}
