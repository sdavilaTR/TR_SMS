package com.example.hassiwrapper.ui.common

/**
 * Una pantalla que tiene algo que perder si el usuario se va a medias.
 *
 * No se usa OnBackPressedCallback para esto: MainActivity sobrescribe onBackPressed() sin llamar a
 * super.onBackPressed(), que es justo lo que despacharia al OnBackPressedDispatcher, asi que
 * cualquier callback registrado ahi nunca llega a ejecutarse. Y la flecha Atras de la barra
 * superior sale por onSupportNavigateUp(), que por diseno tampoco pasa por el dispatcher.
 *
 * MainActivity pregunta por esta interfaz desde los dos sitios, de modo que el boton del sistema y
 * la flecha de la barra se comportan igual.
 */
interface LeaveGuard {

    /**
     * Se llama antes de abandonar la pantalla.
     *
     * @return true si la pantalla se hace cargo (por ejemplo mostrando una confirmacion) y por
     *         tanto NO debe navegarse; false para dejar que la navegacion siga su curso normal.
     */
    fun onLeaveRequested(): Boolean
}
