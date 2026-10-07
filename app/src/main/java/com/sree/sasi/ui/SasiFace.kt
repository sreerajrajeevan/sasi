package com.sree.sasi.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import com.sree.sasi.R

/** Sasi the cat, rendered from the same realistic avatar the overlay uses. */
@Composable
fun SasiFace(size: Dp, imageRes: Int = R.drawable.cat_real_normal) {
    Image(
        painter = painterResource(id = imageRes),
        contentDescription = "Sasi the cat",
        modifier = Modifier.size(size),
    )
}
